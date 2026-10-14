"""Flights: schedule, upload, process (spec sections 36.2 and 17).

A survey's role decides what it is compared against, never its timestamp. A field season holds
at most one active survey per role; re-flying a role is allowed once the earlier one failed or
was cancelled.
"""

from __future__ import annotations

import io
import uuid
import zipfile
from datetime import timedelta
from pathlib import Path
from typing import BinaryIO

from fastapi import UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core import clock
from app.core.config import get_settings
from app.core.errors import Conflict, Invalid, NotFound, TooLarge
from app.domain import geo
from app.domain.enums import ACTIVE_SURVEY_STATUSES, SURVEY_ROLE_META, JobStatus, SurveyRole, SurveyStatus
from app.models import Field, FieldSeason, ProcessingJob, Survey, SurveyImage, SurveySurface, User
from app.pipeline import exif, orthotiles
from app.schemas.common import LatLon
from app.schemas.surveys import (
    FlightImageOut, FlightImagesOut, JobOut, OrthoBounds, OrthomosaicOut, ProcessIn, SurveyCreate, SurveyOut, SurveyPatch,
    UploadResult,
)
from app.services import access, analysis, ids, journal
from app.services.storage import get_storage, safe_name, sniff_image

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".dng", ".webp", ".heic"}
#: Formats whose EXIF GPS block the station reads (drone cameras write JPEG or DNG).
GPS_READABLE = {"image/jpeg", "image/tiff"}


def latest_job(db: Session, survey_id: str) -> ProcessingJob | None:
    return db.scalars(
        select(ProcessingJob).where(ProcessingJob.survey_id == survey_id).order_by(ProcessingJob.created_at.desc())
    ).first()


def survey_out(db: Session, s: Survey, job: ProcessingJob | None = None) -> SurveyOut:
    field = s.season.field
    job = job or latest_job(db, s.id)
    return SurveyOut(
        id=s.id, field_season_id=s.field_season_id, field_id=field.id, field_name=field.name,
        role=s.role, role_label=SURVEY_ROLE_META[SurveyRole(s.role)]["label"], status=s.status,  # type: ignore[arg-type]
        flown_at=s.flown_at, altitude_m=s.altitude_m, sensor=s.sensor, gsd_cm=s.gsd_cm, images=s.images,
        image_bytes=s.image_bytes, progress=s.progress, stage=s.stage, source=s.source, error=s.error,
        pipeline=s.pipeline, model_version=s.model_version, processed_at=s.processed_at,
        has_orthomosaic=bool(s.orthomosaic_key) and get_storage().exists(s.orthomosaic_key),
        has_surface=s.status == SurveyStatus.READY and db.get(SurveySurface, s.id) is not None,
        job=JobOut.model_validate(job) if job else None,
        created_at=s.created_at, updated_at=s.updated_at,
    )


def list_surveys(
    db: Session, user: User, *, field_id: str | None = None, status: str | None = None, role: str | None = None,
    season_id: str | None = None,
) -> list[Survey]:
    q = select(Survey).join(FieldSeason, Survey.field_season_id == FieldSeason.id).join(Field, FieldSeason.field_id == Field.id)
    q = q.where(Field.archived_at.is_(None))
    scope = access.field_scope(user)
    if scope is not None:
        q = q.where(Field.id.in_(scope or {"__none__"}))
    if field_id:
        q = q.where(Field.id == field_id)
    if status:
        q = q.where(Survey.status == status)
    if role:
        q = q.where(Survey.role == role)
    if season_id:
        q = q.where(Survey.field_season_id == season_id)
    return list(db.scalars(q.order_by(Survey.flown_at, Survey.id)).all())


def get_survey(db: Session, user: User, survey_id: str) -> Survey:
    s = db.get(Survey, survey_id)
    if s is None or not access.can_see_field(user, s.season.field_id):
        raise NotFound(f"Survey {survey_id} not found")
    return s


def _role_holder(db: Session, season_id: str, role: str, exclude: str | None = None) -> Survey | None:
    q = select(Survey).where(
        Survey.field_season_id == season_id, Survey.role == role,
        Survey.status.in_([*ACTIVE_SURVEY_STATUSES, SurveyStatus.SCHEDULED]),
    )
    if exclude:
        q = q.where(Survey.id != exclude)
    return db.scalars(q).first()


def schedule_survey(db: Session, user: User, data: SurveyCreate) -> Survey:
    access.require_decider(user, "Scheduling a flight")
    f = access.get_field(db, user, data.field_id)
    season = analysis.current_season(db, f)
    if season is None:
        raise Conflict(f"{f.name} has no open season; open one first")
    holder = _role_holder(db, season.id, data.role)
    if holder is not None:
        raise Conflict(
            f"{f.name} already has a {SURVEY_ROLE_META[data.role]['label'].lower()} flight ({holder.id}, {holder.status.lower()})",
            details={"surveyId": holder.id},
        )
    if data.role != SurveyRole.PRE and _role_holder(db, season.id, SurveyRole.PRE) is None:
        raise Conflict("A follow-up needs a pre-treatment flight to compare against")
    when = data.flown_at or _default_flight_time(db, season, data.role)
    s = Survey(
        id=ids.next_id(db, "survey", Survey), field_season_id=season.id, role=data.role, status=SurveyStatus.SCHEDULED,
        flown_at=when, altitude_m=data.altitude_m, sensor=data.sensor, gsd_cm=0.0, progress=0.0, created_by=user.id,
    )
    db.add(s)
    db.flush()
    journal.change(db, entity="survey", op="schedule", entity_id=s.id, user=user, owned_by_mobile=False,
                   summary=f"{f.name} · {SURVEY_ROLE_META[data.role]['label']} flight scheduled")
    journal.audit(db, user, "Flight scheduled", f"{s.id} · {f.name} · {SURVEY_ROLE_META[data.role]['short']} · {clock.local_date(when)}",
                  entity="survey", entity_id=s.id)
    return s


def _default_flight_time(db: Session, season: FieldSeason, role: str):
    pre = analysis.survey_for_role(analysis.season_surveys(db, season), SurveyRole.PRE)
    offset = SURVEY_ROLE_META[SurveyRole(role)]["offsetDays"]
    base = clock.local_date(pre.flown_at) if pre and role != SurveyRole.PRE else clock.today() + timedelta(days=3)
    d = base + timedelta(days=offset)
    return clock.local(d.day, d.month, d.year, 9, 0)


def patch_survey(db: Session, user: User, s: Survey, data: SurveyPatch) -> Survey:
    access.require_decider(user, "A flight")
    if s.status not in (SurveyStatus.SCHEDULED, SurveyStatus.FAILED):
        raise Conflict("Only a scheduled or failed flight can be edited")
    for k, v in data.model_dump(exclude_unset=True).items():
        if v is not None:
            setattr(s, k, v)
    s.updated_at = clock.now()
    journal.audit(db, user, "Flight updated", f"{s.id} · {s.season.field.name}", entity="survey", entity_id=s.id)
    return s


def cancel_survey(db: Session, user: User, s: Survey) -> None:
    access.require_decider(user, "Cancelling a flight")
    if s.status not in (SurveyStatus.SCHEDULED, SurveyStatus.FAILED):
        raise Conflict("Only a scheduled or failed flight can be cancelled")
    get_storage().delete_prefix(f"flights/{s.id}")
    name = s.season.field.name
    journal.change(db, entity="survey", op="cancel", entity_id=s.id, user=user, owned_by_mobile=False,
                   summary=f"{name} · flight {s.id} cancelled")
    journal.audit(db, user, "Flight cancelled", f"{s.id} · {name}", entity="survey", entity_id=s.id)
    db.delete(s)


def open_upload(db: Session, user: User, field_id: str, role: SurveyRole, flown_at=None) -> Survey:
    """The survey an upload goes into: the role's scheduled or failed flight, else a new one."""
    access.require_decider(user, "Uploading a flight")
    f = access.get_field(db, user, field_id)
    season = analysis.current_season(db, f)
    if season is None:
        raise Conflict(f"{f.name} has no open season")
    holders = db.scalars(select(Survey).where(Survey.field_season_id == season.id, Survey.role == role)).all()
    active = [s for s in holders if s.status in ACTIVE_SURVEY_STATUSES]
    if active:
        raise Conflict(
            f"{f.name} already has a {SURVEY_ROLE_META[role]['label'].lower()} flight that is {active[0].status.lower()}",
            details={"surveyId": active[0].id},
        )
    if role != SurveyRole.PRE and not any(
        s.role == SurveyRole.PRE and s.status in ACTIVE_SURVEY_STATUSES for s in analysis.season_surveys(db, season)
    ):
        raise Conflict("Upload the pre-treatment flight first; a follow-up is compared against it")
    reuse = next((s for s in holders if s.status in (SurveyStatus.SCHEDULED, SurveyStatus.FAILED)), None)
    if reuse is not None:
        reuse.flown_at = flown_at or clock.now()
        reuse.error = None
        return reuse
    s = Survey(
        id=ids.next_id(db, "survey", Survey), field_season_id=season.id, role=role, status=SurveyStatus.SCHEDULED,
        flown_at=flown_at or clock.now(), altitude_m=15, sensor="DJI Mavic 3M · 20 MP RGB", progress=0.0, created_by=user.id,
    )
    db.add(s)
    db.flush()
    return s


def _accept_image(s: Survey, name: str, stream: BinaryIO, content_type: str | None) -> tuple[SurveyImage | None, str | None]:
    settings = get_settings()
    head = stream.read(16)
    stream.seek(0)
    kind = sniff_image(head)
    ext = Path(name).suffix.lower()
    if kind is None or ext not in IMAGE_EXTS:
        return None, "not a JPEG, PNG, TIFF, DNG, WebP or HEIC image"
    storage = get_storage()
    key = storage.new_key(f"flights/{s.id}/images", name)
    try:
        size = storage.save_stream(key, stream, settings.max_flight_image_mb * 1024 * 1024)
    except TooLarge as exc:
        return None, exc.message
    fix = exif.read_gps(storage.path(key)) if kind in GPS_READABLE else None
    return SurveyImage(survey_id=s.id, filename=safe_name(name), storage_key=key, size_bytes=size, content_type=kind,
                       uploaded_at=clock.now(), lat=fix.lat if fix else None, lon=fix.lon if fix else None,
                       alt_m=fix.alt_m if fix else None), None


def add_images(db: Session, user: User, s: Survey, files: list[UploadFile]) -> UploadResult:
    """Store a batch of images. Re-sending a file already held (same name and size) is a no-op,
    so a browser can retry a batch, or resume an interrupted upload, without duplicating images."""
    access.require_decider(user, "Uploading a flight")
    if s.status not in (SurveyStatus.SCHEDULED, SurveyStatus.FAILED):
        raise Conflict(f"Flight {s.id} is {s.status.lower()}; images can only be added before processing")
    settings = get_settings()
    received = duplicates = 0
    rejected: list[dict] = []
    stored = list(db.scalars(select(SurveyImage).where(SurveyImage.survey_id == s.id)))
    held = {(img.filename, img.size_bytes) for img in stored}
    count = len(stored)
    for up in files:
        name = up.filename or "image"
        candidates: list[tuple[str, BinaryIO, int | None]] = []
        if name.lower().endswith(".zip"):
            try:
                zf = zipfile.ZipFile(up.file)
            except zipfile.BadZipFile:
                rejected.append({"file": name, "reason": "not a valid zip archive"})
                continue
            for info in zf.infolist():
                if info.is_dir() or Path(info.filename).suffix.lower() not in IMAGE_EXTS:
                    continue
                if Path(info.filename).name.startswith("._"):  # macOS resource forks inside zips
                    continue
                if info.file_size > settings.max_flight_image_mb * 1024 * 1024:
                    rejected.append({"file": info.filename, "reason": "image too large"})
                    continue
                candidates.append((Path(info.filename).name, zf.open(info), info.file_size))  # type: ignore[arg-type]
        else:
            candidates.append((name, up.file, None))
        for cname, stream, size in candidates:
            if size is None:
                stream.seek(0, io.SEEK_END)
                size = stream.tell()
                stream.seek(0)
            if (safe_name(cname), size) in held:
                duplicates += 1
                continue
            if count >= settings.max_flight_images:
                rejected.append({"file": cname, "reason": f"a flight holds at most {settings.max_flight_images} images"})
                continue
            if not stream.seekable():  # the type check reads the head, then rewinds
                stream = io.BytesIO(stream.read())
            img, reason = _accept_image(s, cname, stream, None)
            if img is None:
                rejected.append({"file": cname, "reason": reason})
                continue
            db.add(img)
            held.add((img.filename, img.size_bytes))
            received += 1
            count += 1
            s.image_bytes = (s.image_bytes or 0) + img.size_bytes
    s.images = count
    s.updated_at = clock.now()
    db.flush()
    with_gps = int(db.scalar(select(func.count()).select_from(SurveyImage).where(
        SurveyImage.survey_id == s.id, SurveyImage.lat.is_not(None))) or 0)
    return UploadResult(survey_id=s.id, received=received, duplicates=duplicates, rejected=rejected, images=s.images,
                        image_bytes=s.image_bytes, with_gps=with_gps)


def clear_images(db: Session, user: User, s: Survey) -> None:
    """Remove every uploaded image, to start the upload again (wrong card, wrong field)."""
    access.require_decider(user, "Uploading a flight")
    if s.status not in (SurveyStatus.SCHEDULED, SurveyStatus.FAILED):
        raise Conflict(f"Flight {s.id} is {s.status.lower()}; its images can only be removed before processing")
    storage = get_storage()
    for img in db.scalars(select(SurveyImage).where(SurveyImage.survey_id == s.id)):
        storage.delete(img.storage_key)
        db.delete(img)
    s.images = 0
    s.image_bytes = 0
    s.updated_at = clock.now()
    journal.audit(db, user, "Flight images removed", f"{s.id} · {s.season.field.name}", entity="survey", entity_id=s.id)


def flight_images(db: Session, s: Survey) -> FlightImagesOut:
    """What has been uploaded so far, and whether it can be placed on the map."""
    imgs = list(db.scalars(select(SurveyImage).where(SurveyImage.survey_id == s.id).order_by(SurveyImage.id)))
    fixes = [(i.lat, i.lon) for i in imgs if i.lat is not None and i.lon is not None]
    centre = offset = None
    if fixes:
        clat = sum(p[0] for p in fixes) / len(fixes)
        clon = sum(p[1] for p in fixes) / len(fixes)
        centre = LatLon(lat=round(clat, 7), lon=round(clon, 7))
        offset = round(_offset_from_field(s.season.field, clat, clon), 1)
    return FlightImagesOut(
        survey_id=s.id, images=len(imgs), image_bytes=sum(i.size_bytes for i in imgs), with_gps=len(fixes),
        gps_centre=centre, offset_from_field_m=offset,
        files=[FlightImageOut(filename=i.filename, size_bytes=i.size_bytes, has_gps=i.lat is not None) for i in imgs],
    )


def _offset_from_field(f: Field, lat: float, lon: float) -> float:
    """Metres from a point to the field (0 inside it)."""
    ring = analysis.boundary_of(f)
    x, y = geo.from_latlon(f.lat, f.lon, lat, lon)
    if geo.contains(ring, x, y):
        return 0.0
    best = float("inf")
    for k in range(len(ring)):
        (ax, ay), (bx, by) = ring[k], ring[(k + 1) % len(ring)]
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        t = 0.0 if length2 == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / length2))
        best = min(best, geo.dist((x, y), (ax + t * dx, ay + t * dy)))
    return best


def _check_photogrammetry_inputs(db: Session, s: Survey) -> None:
    """Refuse, in seconds, a flight OpenDroneMap would spend an hour failing on."""
    from app.pipeline.runner import engines

    if not engines()[0].requires_images:
        return  # the simulator draws no picture, so it needs no positions
    settings = get_settings()
    summary = flight_images(db, s)
    if summary.images < settings.min_flight_images:
        raise Invalid(f"Photogrammetry needs at least {settings.min_flight_images} overlapping images; "
                      f"this flight has {summary.images}")
    if summary.with_gps * 2 < summary.images:
        raise Invalid(
            f"{summary.images - summary.with_gps} of {summary.images} images carry no GPS position, so the orthomosaic "
            "could not be placed on the map. Upload the original JPEG or DNG files from the drone's SD card "
            "(edited or exported copies often lose their GPS data)",
            details={"images": summary.images, "withGps": summary.with_gps},
        )
    limit = settings.max_flight_offset_m
    if limit > 0 and summary.offset_from_field_m is not None and summary.offset_from_field_m > limit:
        km = summary.offset_from_field_m / 1000
        raise Invalid(
            f"These images were taken about {km:.1f} km from {s.season.field.name}. Check that the flight was "
            "uploaded against the right field",
            details={"offsetM": summary.offset_from_field_m, "gpsCentre": summary.gps_centre.model_dump() if summary.gps_centre else None},
        )


def start_processing(db: Session, user: User, s: Survey, data: ProcessIn) -> ProcessingJob:
    access.require_decider(user, "Processing a flight")
    if s.status not in (SurveyStatus.SCHEDULED, SurveyStatus.FAILED):
        raise Conflict(f"Flight {s.id} is already {s.status.lower()}")
    settings = get_settings()
    if data.simulate_images:
        if settings.environment == "production":
            raise Invalid("Simulated flights are not accepted in production")
        if s.images == 0:
            s.images = data.simulate_images
            s.image_bytes = data.simulate_images * 7_600_000
    if s.images == 0:
        raise Invalid("Upload the flight's images before processing it")
    if not data.simulate_images:
        _check_photogrammetry_inputs(db, s)
    if s.role != SurveyRole.PRE:
        season = s.season
        pre = analysis.survey_for_role(analysis.season_surveys(db, season), SurveyRole.PRE)
        if pre is None or pre.status not in ACTIVE_SURVEY_STATUSES:
            raise Conflict("Process the pre-treatment flight first")
    s.status = SurveyStatus.QUEUED
    s.progress = 0.0
    s.stage = "Queued"
    s.error = None
    s.source = data.source or s.source or (f"{s.images} images")
    job = ProcessingJob(id=str(uuid.uuid4()), survey_id=s.id, status=JobStatus.QUEUED, stage="Queued", progress=0.0)
    db.add(job)
    db.flush()
    field = s.season.field
    journal.audit(db, user, "Flight uploaded",
                  f"{s.id} · {field.name} · {SURVEY_ROLE_META[SurveyRole(s.role)]['short']} · {s.images} images",
                  entity="survey", entity_id=s.id)
    return job


def retry(db: Session, user: User, s: Survey) -> ProcessingJob:
    if s.status != SurveyStatus.FAILED:
        raise Conflict("Only a failed flight can be retried")
    return start_processing(db, user, s, ProcessIn())


def orthomosaic_path(s: Survey) -> Path:
    if not s.orthomosaic_key:
        raise NotFound("This flight has no orthomosaic")
    return get_storage().open(s.orthomosaic_key)


def orthomosaic_version(s: Survey) -> str:
    """Changes whenever the flight is processed again, so tile URLs can be cached for good."""
    stamp = s.processed_at or s.updated_at
    return str(int(stamp.timestamp())) if stamp else "0"


def orthomosaic_out(s: Survey) -> OrthomosaicOut:
    path = orthomosaic_path(s)
    try:
        info = orthotiles.describe(path)
    except orthotiles.OrthomosaicUnreadable as exc:
        raise Conflict(str(exc)) from exc
    v = orthomosaic_version(s)
    return OrthomosaicOut(
        survey_id=s.id, field_id=s.season.field_id, role=s.role, flown_at=s.flown_at, processed_at=s.processed_at,
        bounds=OrthoBounds(west=info.west, south=info.south, east=info.east, north=info.north),
        min_zoom=info.min_zoom, max_zoom=info.max_zoom, gsd_cm=s.gsd_cm, width=info.width, height=info.height,
        epsg=info.epsg, size_bytes=info.size_bytes, version=v,
        tile_url=f"/surveys/{s.id}/orthomosaic/tiles/{{z}}/{{x}}/{{y}}.webp?v={v}",
        download_url=f"/surveys/{s.id}/orthomosaic",
    )
