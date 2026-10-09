"""Leaf scans and the abstention review loop (spec sections 26 and 39).

The phone classifies on the device and either names a species or abstains. An abstained scan
is not a gap: it is an instruction to send a person to look. The operator may suggest a label
in the field; the analyst resolves it on the dashboard.
"""

from __future__ import annotations

from fastapi import UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core import clock
from app.core.config import get_settings
from app.core.errors import Conflict, Invalid, NotFound
from app.domain import geo
from app.domain.enums import ScanStatus, WeedClass
from app.models import Device, Field, LeafScan, Species, User
from app.schemas.observations import AnnotateIn, ResolveIn, ScanCreate, ScanOut
from app.services import access, analysis, ids, journal
from app.services.storage import get_storage, sniff_image
from app.services.zones import find_zone


def scan_status(s: LeafScan) -> ScanStatus:
    if not s.abstained:
        return ScanStatus.CONFIDENT
    if s.resolved:
        return ScanStatus.RESOLVED
    if s.annotation:
        return ScanStatus.ANNOTATED
    return ScanStatus.NEEDS_LABEL


def scan_out(db: Session, s: LeafScan, names: dict[str, str] | None = None) -> ScanOut:
    field = db.get(Field, s.field_id)
    device = db.get(Device, s.device_id) if s.device_id else None

    def name(uid: str | None) -> str | None:
        if uid is None:
            return None
        if names is not None and uid in names:
            return names[uid]
        u = db.get(User, uid)
        return u.name if u else None

    label = s.resolution or s.annotation or (None if s.abstained else s.species_latin) or "Needs review"
    return ScanOut(
        id=s.id, client_id=s.client_id, at=s.captured_at, field_id=s.field_id, field_name=field.name if field else s.field_id,
        zone_label=s.zone_label, frame_id=s.frame_id, species_latin=s.species_latin, species_local=s.species_local,
        weed_class=s.weed_class, confidence=s.confidence, abstained=s.abstained,  # type: ignore[arg-type]
        runner_up=[(str(a), float(b)) for a, b in (s.runner_up or [])], inference_ms=s.inference_ms,
        model_version=s.model_version, leaf_seed=s.leaf_seed, lat=s.lat, lon=s.lon, gnss_accuracy_m=s.gnss_accuracy_m,
        device_id=s.device_id, device_name=device.name if device else None, operator_id=s.operator_id,
        has_photo=bool(s.photo_key), status=scan_status(s), annotation=s.annotation, annotation_note=s.annotation_note,
        annotated_by=name(s.annotated_by), annotated_at=s.annotated_at, resolved=s.resolved, resolution=s.resolution,
        resolution_note=s.resolution_note, resolved_by=name(s.resolved_by), resolved_at=s.resolved_at, display_label=label,
    )


def get_scan(db: Session, user: User, scan_id: str) -> LeafScan:
    s = db.get(LeafScan, scan_id)
    if s is None or not access.can_see_field(user, s.field_id):
        raise NotFound(f"Scan {scan_id} not found")
    return s


def list_scans(
    db: Session, user: User, *, field_id: str | None = None, status: ScanStatus | None = None,
    device_id: str | None = None, zone_label: str | None = None, limit: int = 50, offset: int = 0,
) -> tuple[list[LeafScan], int]:
    q = select(LeafScan).join(Field, Field.id == LeafScan.field_id).where(Field.archived_at.is_(None))
    scope = access.field_scope(user)
    if scope is not None:
        q = q.where(LeafScan.field_id.in_(scope or {"__none__"}))
    if field_id:
        q = q.where(LeafScan.field_id == field_id)
    if device_id:
        q = q.where(LeafScan.device_id == device_id)
    if zone_label:
        q = q.where(LeafScan.zone_label == zone_label)
    if status == ScanStatus.CONFIDENT:
        q = q.where(LeafScan.abstained.is_(False))
    elif status == ScanStatus.RESOLVED:
        q = q.where(LeafScan.abstained.is_(True), LeafScan.resolved.is_(True))
    elif status == ScanStatus.ANNOTATED:
        q = q.where(LeafScan.abstained.is_(True), LeafScan.resolved.is_(False), LeafScan.annotation.is_not(None))
    elif status == ScanStatus.NEEDS_LABEL:
        q = q.where(LeafScan.abstained.is_(True), LeafScan.resolved.is_(False), LeafScan.annotation.is_(None))
    total = int(db.scalar(select(func.count()).select_from(q.subquery())) or 0)
    rows = db.scalars(q.order_by(LeafScan.captured_at.desc(), LeafScan.id.desc()).limit(limit).offset(offset)).all()
    return list(rows), total


def _known_species(db: Session, latin: str) -> Species | None:
    return db.get(Species, latin)


def create_scan(db: Session, user: User, data: ScanCreate, *, device_id: str | None = None, client_seq: int | None = None) -> tuple[LeafScan, bool]:
    existing = db.scalar(select(LeafScan).where(LeafScan.client_id == data.client_id))
    if existing is not None:
        return existing, False
    f = access.get_field(db, user, data.field_id)
    sp = _known_species(db, data.species_latin)
    weed_class = data.weed_class or (WeedClass(sp.cls) if sp else None)
    if weed_class is None:
        raise Invalid("weedClass is required for a species outside the station's label set")
    lat, lon = data.lat, data.lon
    if lat is None or lon is None:
        # No fix: place the scan at its zone's centre (or the field's) so it still maps.
        anchor = analysis.gate_of(f)
        if data.zone_label:
            try:
                z = find_zone(db, f, data.zone_label)
                anchor = (z.cx, z.cy)
            except NotFound:
                anchor = geo.centroid(analysis.boundary_of(f))
        else:
            anchor = geo.centroid(analysis.boundary_of(f))
        lat, lon = geo.to_latlon(f.lat, f.lon, anchor)
    s = LeafScan(
        id=ids.next_id(db, "scan", LeafScan), client_id=data.client_id, device_id=device_id, operator_id=user.id,
        captured_at=data.captured_at or clock.now(), field_id=f.id, zone_label=data.zone_label, frame_id=data.frame_id,
        species_latin=data.species_latin, species_local=data.species_local or (sp.local if sp else None),
        weed_class=weed_class.value, confidence=data.confidence, abstained=data.abstained,
        runner_up=[[a, b] for a, b in data.runner_up], inference_ms=data.inference_ms,
        model_version=data.model_version, leaf_seed=data.leaf_seed, lat=lat, lon=lon, gnss_accuracy_m=data.gnss_accuracy_m,
    )
    db.add(s)
    db.flush()
    journal.change(
        db, entity="leaf_scan", op="create", entity_id=s.id, user=user, device_id=device_id, client_seq=client_seq,
        at=s.captured_at, payload=data.model_dump(mode="json"),
        summary=f"Scan {s.id} sent to review queue" if s.abstained else f"Scan {s.id} · {s.species_latin}",
    )
    return s, True


def attach_photo(db: Session, user: User, s: LeafScan, upload: UploadFile) -> LeafScan:
    if not access.is_decider(user) and s.operator_id != user.id:
        raise Conflict("Only the operator who took a scan can attach its photo")
    head = upload.file.read(16)
    upload.file.seek(0)
    kind = sniff_image(head)
    if kind not in ("image/jpeg", "image/png", "image/webp"):
        raise Invalid("Scan photos must be JPEG, PNG or WebP")
    storage = get_storage()
    ext = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[kind]
    key = f"scans/{s.id}/photo{ext}"
    size = storage.save_stream(key, upload.file, get_settings().max_scan_photo_mb * 1024 * 1024)
    if s.photo_key and s.photo_key != key:
        storage.delete(s.photo_key)
    s.photo_key, s.photo_content_type, s.photo_bytes = key, kind, size
    s.updated_at = clock.now()
    return s


def annotate(db: Session, user: User, s: LeafScan, data: AnnotateIn, *, device_id: str | None = None, client_seq: int | None = None) -> LeafScan:
    """The operator's suggestion, made in the field. It does not close the review."""
    if not s.abstained:
        raise Conflict("Only an abstained scan takes a label")
    if s.resolved:
        raise Conflict(f"Scan {s.id} was already resolved by the analyst")
    s.annotation = data.species.strip()
    s.annotation_note = data.note
    s.annotated_by = user.id
    s.annotated_at = clock.now()
    s.updated_at = clock.now()
    journal.change(db, entity="abstention", op="annotate", entity_id=s.id, user=user, device_id=device_id, client_seq=client_seq,
                   summary=f"Scan {s.id} annotated · {s.annotation}", payload=data.model_dump())
    return s


def resolve(db: Session, user: User, s: LeafScan, data: ResolveIn) -> LeafScan:
    access.require_decider(user, "Resolving the review queue")
    if not s.abstained:
        raise Conflict("Only an abstained scan needs resolving")
    s.resolved = True
    s.resolution = data.species.strip()
    s.resolution_note = data.note
    s.resolved_by = user.id
    s.resolved_at = clock.now()
    s.updated_at = clock.now()
    journal.audit(db, user, "Scan resolved", f"{s.id} · {s.resolution}", entity="leaf_scan", entity_id=s.id)
    journal.change(db, entity="leaf_scan", op="resolve", entity_id=s.id, user=user, owned_by_mobile=False,
                   summary=f"Scan {s.id} labelled {s.resolution}")
    return s


def reopen(db: Session, user: User, s: LeafScan) -> LeafScan:
    access.require_decider(user, "Re-opening a review")
    if not s.resolved:
        raise Conflict(f"Scan {s.id} is not resolved")
    s.resolved = False
    s.resolution = s.resolution_note = s.resolved_by = None
    s.resolved_at = None
    s.updated_at = clock.now()
    journal.audit(db, user, "Scan re-opened", s.id, entity="leaf_scan", entity_id=s.id)
    return s
