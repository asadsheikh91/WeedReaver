"""Fields: geometry, derived facts, the treatment-loop stepper and parcel capture."""

from __future__ import annotations

import zlib
from datetime import timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import Conflict, Invalid
from app.domain import geo
from app.domain.boundary_io import parse_boundary
from app.domain.enums import Role, Severity, SurveyRole, SurveyStatus, ZoneState
from app.models import (
    ExportJob, Field, FieldSeason, LeafScan, Quadrat, Survey, Treatment, TreatmentZone, User, Verification,
)
from app.schemas.fields import (
    AreaOut, BoundaryPreview, FieldCreate, FieldDetail, FieldOut, FieldPatch, FieldSummary, LoopStep,
    SeasonCreate, SeasonOut, SeasonPatch, SurveyBrief,
)
from app.services import access, analysis, ids, journal
from app.services.station import get_station

MIN_AREA_SQM = 200
MAX_AREA_SQM = 2_000_000
DEFAULT_VILLAGE = "Pindi Bhattian, Hafizabad"
SENSOR = "DJI Mavic 3M · 20 MP RGB"


# ----------------------------------------------------------------------------- serialisation


def area_out(boundary: list[geo.Pt]) -> AreaOut:
    sqm = geo.area(boundary)
    ac = geo.acres(sqm)
    k, m = geo.kanal_marla(ac)
    b = geo.bounds(boundary)
    return AreaOut(
        sqm=round(sqm, 2), acres=ac, hectares=sqm / 10_000, kanal=k, marla=m,
        perimeter_m=round(geo.perimeter(boundary), 2), width_m=b[2] - b[0], height_m=b[3] - b[1],
    )


def season_out(s: FieldSeason | None) -> SeasonOut | None:
    if s is None:
        return None
    days = (clock.today() - s.sowing_date).days if s.sowing_date else None
    return SeasonOut(
        id=s.id, field_id=s.field_id, crop=s.crop, season=s.season, sowing_date=s.sowing_date,
        row_spacing_cm=s.row_spacing_cm, variety=s.variety, harvest_date=s.harvest_date,
        days_since_sowing=days, updated_at=s.updated_at,
    )


def survey_brief(s: Survey | None) -> SurveyBrief | None:
    if s is None:
        return None
    return SurveyBrief(id=s.id, role=s.role, status=s.status, flown_at=s.flown_at, progress=s.progress)


def field_out(f: Field) -> FieldOut:
    boundary = analysis.boundary_of(f)
    return FieldOut(
        id=f.id, name=f.name, village=f.village,
        boundary=[{"x": x, "y": y} for x, y in boundary],  # type: ignore[misc]
        lat=f.lat, lon=f.lon, gate={"x": f.gate[0], "y": f.gate[1]},  # type: ignore[arg-type]
        landscape=f.landscape or {}, capture_method=f.capture_method, captured_by=f.captured_by_name,
        area=area_out(boundary), archived=f.archived_at is not None, created_at=f.created_at, updated_at=f.updated_at,
    )


def pressure_of(zones: list[TreatmentZone]) -> Severity:
    open_ = [z for z in zones if not ZoneState(z.state).done]
    if any(z.severity == Severity.HEAVY for z in open_):
        return Severity.HEAVY
    if any(z.severity == Severity.MODERATE for z in open_):
        return Severity.MODERATE
    return Severity.CLEAN


def _loop(ctx: analysis.FieldContext, zones: list[TreatmentZone], recorded: bool, verified: bool) -> list[LoopStep]:
    surveyed = ctx.surveyed
    clean = surveyed and not zones
    done = {
        "survey": surveyed,
        "route": surveyed and (clean or all(z.state != ZoneState.FLAGGED for z in zones)),
        "treat": surveyed and (clean or all(ZoneState(z.state).done for z in zones)),
        "record": surveyed and (clean or recorded),
        "verify": surveyed and (clean or verified),
    }
    labels = {"survey": "Survey", "route": "Route", "treat": "Treat", "record": "Record", "verify": "Verify"}
    current = next((k for k in labels if not done[k]), None)
    return [LoopStep(key=k, label=labels[k], done=done[k], current=k == current) for k in labels]  # type: ignore[arg-type]


def _season_facts(db: Session, ctx: analysis.FieldContext) -> tuple[list[TreatmentZone], bool, Verification | None]:
    zones = analysis.active_zones(db, ctx.season)
    recorded = False
    verification = None
    if ctx.season is not None:
        recorded = db.scalar(select(func.count()).select_from(Treatment).where(Treatment.field_season_id == ctx.season.id)) > 0
        verification = db.scalars(
            select(Verification).where(Verification.field_season_id == ctx.season.id).order_by(Verification.saved_at.desc())
        ).first()
    return zones, recorded, verification


def field_summary(db: Session, f: Field) -> FieldSummary:
    ctx = analysis.context(db, f)
    zones, recorded, verification = _season_facts(db, ctx)
    ready = [s for s in ctx.surveys if s.status == SurveyStatus.READY]
    upcoming = sorted(
        (s for s in ctx.surveys if s.status in (SurveyStatus.SCHEDULED, SurveyStatus.QUEUED, SurveyStatus.PROCESSING)),
        key=lambda s: s.flown_at,
    )
    last = max(ready, key=lambda s: s.flown_at) if ready else None
    done = sum(1 for z in zones if ZoneState(z.state).done)
    base = field_out(f).model_dump()
    return FieldSummary(
        **base,
        season=season_out(ctx.season),
        surveyed=ctx.surveyed,
        pressure=pressure_of(zones),
        zones_total=len(zones),
        zones_done=done,
        zones_open=len(zones) - done,
        last_survey=survey_brief(last),
        next_survey=survey_brief(upcoming[0] if upcoming else None),
        loop=_loop(ctx, zones, recorded, verification is not None),
        verified_at=verification.saved_at if verification else None,
    )


def _up_next(ctx: analysis.FieldContext, s: FieldSummary, zones: list[TreatmentZone]) -> str:
    current = next((st.key for st in s.loop if st.current), None)
    if current is None:
        return "No treatment indicated" if s.surveyed and not zones else "Loop complete for this season"
    if current == "survey":
        pre = ctx.by_role(SurveyRole.PRE)
        if pre is None:
            return "Schedule the pre-treatment flight"
        if pre.status in (SurveyStatus.QUEUED, SurveyStatus.PROCESSING):
            return f"Pre-treatment flight processing · {round(pre.progress * 100)}%"
        if pre.status == SurveyStatus.FAILED:
            return "Pre-treatment flight failed processing · re-upload"
        return f"Pre-treatment flight {_in_days(pre.flown_at)}"
    flagged = [z for z in zones if z.state == ZoneState.FLAGGED]
    open_ = [z for z in zones if not ZoneState(z.state).done]
    if current == "route":
        return f"Plan the spray route · {len(flagged)} {'zone' if len(flagged) == 1 else 'zones'} flagged"
    if current == "treat":
        sqm = sum(z.area_sqm for z in open_)
        return f"Treat {len(open_)} {'zone' if len(open_) == 1 else 'zones'} · {sqm:,} m²"
    if current == "record":
        return "Record what was applied"
    follow = ctx.by_role(SurveyRole.PLUS_14D)
    if follow is None:
        return "Schedule the +14 d follow-up flight"
    if follow.status == SurveyStatus.READY:
        return "Verify treatment against the +14 d flight"
    if follow.status in (SurveyStatus.QUEUED, SurveyStatus.PROCESSING):
        return f"+14 d flight processing · {round(follow.progress * 100)}%"
    return f"+14 d follow-up flight {_in_days(follow.flown_at)}"


def _in_days(when) -> str:
    d = (clock.local_date(when) - clock.today()).days
    if d == 0:
        return "today"
    if d == 1:
        return "tomorrow"
    if d == -1:
        return "was due yesterday"
    return f"in {d} days" if d > 1 else f"was due {-d} days ago"


def field_detail(db: Session, f: Field) -> FieldDetail:
    s = field_summary(db, f)
    ctx = analysis.context(db, f)
    zones = analysis.active_zones(db, ctx.season)

    def count(model, *conds) -> int:
        return int(db.scalar(select(func.count()).select_from(model).where(*conds)) or 0)

    counts = {
        "scans": count(LeafScan, LeafScan.field_id == f.id),
        "scansAwaitingReview": count(LeafScan, LeafScan.field_id == f.id, LeafScan.abstained.is_(True), LeafScan.resolved.is_(False)),
        "treatments": count(Treatment, Treatment.field_id == f.id),
        "quadrats": count(Quadrat, Quadrat.field_id == f.id),
        "exports": count(ExportJob, ExportJob.field_id == f.id),
        "verifications": count(Verification, Verification.field_id == f.id),
    }
    return FieldDetail(
        **s.model_dump(),
        surveys=[survey_brief(x) for x in ctx.surveys],  # type: ignore[misc]
        counts=counts,
        up_next=_up_next(ctx, s, zones),
    )


def list_fields(db: Session, user: User, include_archived: bool = False) -> list[FieldSummary]:
    rows = db.scalars(access.visible_fields_query(user, include_archived)).all()
    return [field_summary(db, f) for f in rows]


# ----------------------------------------------------------------------------- geometry intake


def _validated_ring(local: list[geo.Pt], tolerance: float | None) -> list[geo.Pt]:
    ring = geo.dedupe_ring(local)
    if tolerance:
        ring = geo.dedupe_ring(geo.simplify_ring(ring, tolerance))
    if len(ring) < 3:
        raise Invalid("A boundary needs at least three distinct vertices")
    if not geo.is_simple(ring):
        raise Invalid("The boundary crosses itself. Redraw it as one closed outline.")
    a = geo.area(ring)
    if a < MIN_AREA_SQM:
        raise Invalid(f"That parcel is only {a:.0f} m²; the smallest a survey can resolve is {MIN_AREA_SQM} m²")
    if a > MAX_AREA_SQM:
        raise Invalid("That parcel is larger than 200 ha; split it into fields")
    return ring


def resolve_boundary(
    boundary_lat_lon: list | None, boundary: list | None, lat: float | None, lon: float | None, tolerance: float | None
) -> tuple[list[geo.Pt], float, float, geo.Pt]:
    """Any accepted boundary input -> (normalised local ring, anchor lat, anchor lon, shift)."""
    if boundary_lat_lon is not None:
        pts = [(float(p.lat), float(p.lon)) for p in boundary_lat_lon]
        lat0 = max(p[0] for p in pts)
        lon0 = min(p[1] for p in pts)
        local = [geo.from_latlon(lat0, lon0, la, lo) for la, lo in pts]
    else:
        assert boundary is not None and lat is not None and lon is not None
        lat0, lon0 = float(lat), float(lon)
        local = [(float(p.x), float(p.y)) for p in boundary]
    ring = _validated_ring(local, tolerance)
    norm, shift = geo.normalise_to_origin(ring)
    a_lat, a_lon = geo.to_latlon(lat0, lon0, shift)
    return norm, round(a_lat, 8), round(a_lon, 8), shift


def _shift_landscape(land: dict | None, shift: geo.Pt) -> dict | None:
    if not land:
        return None
    sx, sy = shift

    def pt(p: dict | None) -> dict | None:
        return None if p is None else {"x": p["x"] - sx, "y": p["y"] - sy}

    out = dict(land)
    for k in ("canal", "road", "watercourse"):
        if out.get(k):
            out[k] = [pt(p) for p in out[k]]
    for k in ("farmstead", "village", "tubewell"):
        if out.get(k):
            out[k] = pt(out[k])
    return out


def _default_landscape(field_id: str, ring: list[geo.Pt]) -> dict:
    seed = zlib.crc32(field_id.encode()) % 100_000
    b = geo.bounds(ring)
    return {"seed": seed, "mustardBias": 0.14, "road": [{"x": -14.0, "y": -200.0}, {"x": -13.0, "y": b[3] + 220.0}]}


def _check_gate(ring: list[geo.Pt], gate: geo.Pt) -> geo.Pt:
    near = geo.contains(ring, *gate) or min(
        geo.dist(gate, ring[i]) for i in range(len(ring))
    ) <= 40 or any(
        _seg_dist(gate, ring[i], ring[(i + 1) % len(ring)]) <= 30 for i in range(len(ring))
    )
    if not near:
        raise Invalid("The gate must be on or near the field boundary")
    return gate


def _seg_dist(p: geo.Pt, a: geo.Pt, b: geo.Pt) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2))
    return geo.dist(p, (a[0] + t * dx, a[1] + t * dy))


def season_id_for(db: Session, field_id: str, label: str) -> str:
    base = "FS-" + field_id.split("-", 1)[-1]
    if db.get(FieldSeason, base) is None:
        return base
    digits = "".join(ch for ch in label if ch.isdigit())[-4:] or str(clock.today().year)
    candidate = f"{base}-{digits}"
    n = 2
    while db.get(FieldSeason, candidate) is not None:
        candidate = f"{base}-{digits}-{n}"
        n += 1
    return candidate


def create_field(
    db: Session, user: User, data: FieldCreate, *, device_id: str | None = None, client_seq: int | None = None
) -> tuple[Field, bool]:
    """Returns (field, created). A repeated client_id returns the existing field."""
    if data.client_id:
        existing = db.scalar(select(Field).where(Field.client_id == data.client_id))
        if existing is not None:
            return existing, False
    ring, lat, lon, shift = resolve_boundary(data.boundary_lat_lon, data.boundary, data.lat, data.lon, data.simplify_tolerance_m)
    field_id = ids.next_id(db, "field", Field)
    gate = (data.gate.x - shift[0], data.gate.y - shift[1]) if data.gate else ring[0]
    gate = _check_gate(ring, gate)
    landscape = _shift_landscape(data.landscape.model_dump(by_alias=True, exclude_none=True) if data.landscape else None, shift)
    f = Field(
        id=field_id, client_id=data.client_id, name=data.name.strip(),
        village=(data.village or "").strip() or DEFAULT_VILLAGE,
        boundary=[[x, y] for x, y in ring], lat=lat, lon=lon, gate=[round(gate[0], 3), round(gate[1], 3)],
        landscape=landscape or _default_landscape(field_id, ring), capture_method=data.capture_method,
        captured_by_id=user.id, captured_by_name=user.name,
        area_sqm=round(geo.area(ring), 3), perimeter_m=round(geo.perimeter(ring), 3),
    )
    db.add(f)
    db.flush()
    station = get_station(db)
    season = FieldSeason(
        id=season_id_for(db, field_id, station.season_label), field_id=field_id, crop=data.crop,
        season=station.season_label, sowing_date=data.sowing_date, row_spacing_cm=data.row_spacing_cm, variety=data.variety,
    )
    db.add(season)
    db.flush()
    # A new parcel gets its pre-treatment flight on the calendar straight away.
    flight_day = clock.today() + timedelta(days=5)
    db.add(Survey(
        id=ids.next_id(db, "survey", Survey), field_season_id=season.id, role=SurveyRole.PRE, status=SurveyStatus.SCHEDULED,
        flown_at=clock.local(flight_day.day, flight_day.month, flight_day.year, 9, 0), altitude_m=15, sensor=SENSOR,
        gsd_cm=0.0, images=0, progress=0.0, created_by=user.id,
    ))
    if not user.scope_all:
        user.fields.append(f)  # whoever captures a parcel can see it
    mobile = Role(user.role) in (Role.OPERATOR, Role.TRAINEE)
    journal.change(db, entity="field", op="create", entity_id=field_id, user=user, device_id=device_id, client_seq=client_seq,
                   summary=f"{f.name} added · {f.capture_method}", owned_by_mobile=mobile, payload=f.boundary)
    journal.audit(db, user, "Field added", f"{f.name} · {f.capture_method} · {geo.acres(f.area_sqm):.2f} ac",
                  entity="field", entity_id=field_id)
    db.flush()
    return f, True


def patch_field(db: Session, user: User, f: Field, data: FieldPatch) -> Field:
    changes: list[str] = []
    if (data.name is not None or data.village is not None) and not access.is_decider(user) and f.captured_by_id != user.id:
        access.require_decider(user, "Renaming a field someone else captured")
    if data.name is not None and data.name.strip() != f.name:
        f.name = data.name.strip()
        changes.append("name")
    if data.village is not None and data.village.strip() != f.village:
        f.village = data.village.strip() or DEFAULT_VILLAGE
        changes.append("village")
    geometry_changed = False
    if data.boundary_lat_lon is not None or data.boundary is not None:
        access.require_decider(user, "A field boundary")
        if data.boundary_lat_lon is not None and data.boundary is not None:
            raise Invalid("Provide exactly one of boundaryLatLon or boundary")
        ring, lat, lon, _ = resolve_boundary(data.boundary_lat_lon, data.boundary, f.lat, f.lon, None)
        f.boundary = [[x, y] for x, y in ring]
        f.lat, f.lon = lat, lon
        f.area_sqm = round(geo.area(ring), 3)
        f.perimeter_m = round(geo.perimeter(ring), 3)
        if not (geo.contains(ring, *analysis.gate_of(f)) or _check_gate_ok(ring, analysis.gate_of(f))):
            f.gate = [ring[0][0], ring[0][1]]
        changes.append("boundary")
        geometry_changed = True
    if data.gate is not None:
        access.require_decider(user, "The field gate")
        g = _check_gate(analysis.boundary_of(f), (data.gate.x, data.gate.y))
        f.gate = [g[0], g[1]]
        changes.append("gate")
        geometry_changed = True
    if data.landscape is not None:
        access.require_decider(user, "The field landscape")
        f.landscape = data.landscape.model_dump(by_alias=True, exclude_none=True)
        changes.append("landscape")
    if not changes:
        return f
    f.updated_at = clock.now()
    db.flush()
    if geometry_changed:
        analysis.publish_zones(db, f, actor=user)
    journal.change(db, entity="field", op="update", entity_id=f.id, user=user, owned_by_mobile=False,
                   summary=f"{f.name} · {', '.join(changes)} changed")
    journal.audit(db, user, "Field updated", f"{f.name} · {', '.join(changes)}", entity="field", entity_id=f.id)
    return f


def _check_gate_ok(ring: list[geo.Pt], gate: geo.Pt) -> bool:
    try:
        _check_gate(ring, gate)
        return True
    except Invalid:
        return False


def archive_field(db: Session, user: User, f: Field) -> None:
    access.require_decider(user, "Archiving a field")
    if f.archived_at is not None:
        raise Conflict(f"{f.name} is already archived")
    f.archived_at = clock.now()
    f.updated_at = clock.now()
    journal.change(db, entity="field", op="archive", entity_id=f.id, user=user, owned_by_mobile=False, summary=f"{f.name} archived")
    journal.audit(db, user, "Field archived", f.name, entity="field", entity_id=f.id)


def restore_field(db: Session, user: User, f: Field) -> None:
    access.require_decider(user, "Restoring a field")
    f.archived_at = None
    f.updated_at = clock.now()
    journal.audit(db, user, "Field restored", f.name, entity="field", entity_id=f.id)


def preview_boundary(content: bytes, filename: str) -> BoundaryPreview:
    parsed = parse_boundary(content, filename)
    lat0 = max(p[0] for p in parsed.ring)
    lon0 = min(p[1] for p in parsed.ring)
    local = [geo.from_latlon(lat0, lon0, la, lo) for la, lo in parsed.ring]
    ring = _validated_ring(local, None)
    norm, shift = geo.normalise_to_origin(ring)
    a_lat, a_lon = geo.to_latlon(lat0, lon0, shift)
    return BoundaryPreview(
        boundary=[{"x": x, "y": y} for x, y in norm],  # type: ignore[misc]
        boundary_lat_lon=[{"lat": la, "lon": lo} for la, lo in parsed.ring],  # type: ignore[misc]
        lat=a_lat, lon=a_lon, area=area_out(norm), vertices=len(norm),
        source_format=parsed.source_format, name=parsed.name,  # type: ignore[arg-type]
    )


# ----------------------------------------------------------------------------- seasons


def create_season(db: Session, user: User, f: Field, data: SeasonCreate) -> FieldSeason:
    access.require_decider(user, "A field season")
    label = (data.season or get_station(db).season_label).strip()
    if db.scalar(select(FieldSeason).where(FieldSeason.field_id == f.id, FieldSeason.season == label)):
        raise Conflict(f"{f.name} already has a {label} season")
    s = FieldSeason(
        id=season_id_for(db, f.id, label), field_id=f.id, crop=data.crop, season=label, sowing_date=data.sowing_date,
        row_spacing_cm=data.row_spacing_cm, variety=data.variety,
    )
    db.add(s)
    journal.audit(db, user, "Season opened", f"{f.name} · {label} · {data.crop}", entity="field_season", entity_id=s.id)
    db.flush()
    return s


def patch_season(db: Session, user: User, s: FieldSeason, data: SeasonPatch) -> FieldSeason:
    access.require_decider(user, "A field season")
    for k, v in data.model_dump(exclude_unset=True).items():
        setattr(s, k, v)
    if s.harvest_date and s.sowing_date and s.harvest_date < s.sowing_date:
        raise Invalid("Harvest date cannot be before sowing")
    s.updated_at = clock.now()
    journal.audit(db, user, "Season updated", f"{s.field.name} · {s.season}", entity="field_season", entity_id=s.id)
    db.flush()
    return s
