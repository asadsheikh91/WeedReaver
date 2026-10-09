from __future__ import annotations

import json
from typing import Annotated, Literal

from fastapi import APIRouter, File, Query, Response, UploadFile, status
from sqlalchemy import select

from app.api.deps import CurrentUser, DbDep, OptionalDevice
from app.core.config import get_settings
from app.core.errors import Invalid, NotFound, TooLarge
from app.domain.enums import SurveyRole, ZoneState
from app.models import FieldSeason, TreatmentZone, Verification
from app.schemas.analysis import (
    CellOut, GridCompare, GridStats, RotationOut, RouteOut, VerificationOut, VerificationPreview, VerificationSave,
    ZoneOut, ZonePreview, ZoneStateIn,
)
from app.schemas.common import Message
from app.schemas.fields import (
    BoundaryPreview, FieldCreate, FieldDetail, FieldPatch, FieldSummary, SeasonCreate, SeasonOut, SeasonPatch,
)
from app.services import access, analysis, exports, fields, treatments, verification, zones
from app.services.station import get_station

router = APIRouter(tags=["fields"])

FieldId = Annotated[str, "Field id, e.g. F-047"]
RoleQ = Annotated[Literal["PRE", "PLUS_14D", "PLUS_28D"], Query(alias="survey", description="Which survey's surface")]
SizeQ = Annotated[int | None, Query(alias="size", description="Grid cell size in metres: 1, 2 or 5")]
ThrQ = Annotated[float | None, Query(alias="threshold", description="Prescription threshold %, default: published")]


@router.get("/fields", response_model=list[FieldSummary], summary="Fields with their pressure and place in the loop")
def list_fields(user: CurrentUser, db: DbDep, include_archived: Annotated[bool, Query(alias="includeArchived")] = False):
    return fields.list_fields(db, user, include_archived)


@router.post("/fields", response_model=FieldDetail, status_code=status.HTTP_201_CREATED,
             summary="Add a parcel (walked, drawn or imported). Idempotent on clientId")
def create_field(body: FieldCreate, user: CurrentUser, db: DbDep, device: OptionalDevice, response: Response):
    f, created = fields.create_field(db, user, body, device_id=device.id if device else None)
    db.commit()
    if not created:
        response.status_code = status.HTTP_200_OK
    return fields.field_detail(db, f)


@router.post("/fields/parse-boundary", response_model=BoundaryPreview,
             summary="Read a KML, GeoJSON or CSV boundary and preview it before adding the field")
def parse_boundary(user: CurrentUser, file: Annotated[UploadFile, File()]) -> BoundaryPreview:
    limit = get_settings().max_boundary_file_kb * 1024
    content = file.file.read(limit + 1)
    if len(content) > limit:
        raise TooLarge(f"Boundary files are limited to {get_settings().max_boundary_file_kb} KB")
    return fields.preview_boundary(content, file.filename or "")


@router.get("/fields/{field_id}", response_model=FieldDetail)
def get_field(field_id: FieldId, user: CurrentUser, db: DbDep) -> FieldDetail:
    return fields.field_detail(db, access.get_field(db, user, field_id, include_archived=True))


@router.patch("/fields/{field_id}", response_model=FieldDetail)
def patch_field(field_id: FieldId, body: FieldPatch, user: CurrentUser, db: DbDep) -> FieldDetail:
    f = fields.patch_field(db, user, access.get_field(db, user, field_id), body)
    db.commit()
    return fields.field_detail(db, f)


@router.delete("/fields/{field_id}", response_model=Message, summary="Archive a field (history is kept)")
def archive_field(field_id: FieldId, user: CurrentUser, db: DbDep) -> Message:
    f = access.get_field(db, user, field_id)
    fields.archive_field(db, user, f)
    db.commit()
    return Message(message=f"{f.name} archived")


@router.post("/fields/{field_id}/restore", response_model=FieldDetail)
def restore_field(field_id: FieldId, user: CurrentUser, db: DbDep) -> FieldDetail:
    f = access.get_field(db, user, field_id, include_archived=True)
    fields.restore_field(db, user, f)
    db.commit()
    return fields.field_detail(db, f)


@router.get("/fields/{field_id}/boundary.geojson", summary="The parcel as a GeoJSON Feature (WGS84)")
def boundary_geojson(field_id: FieldId, user: CurrentUser, db: DbDep) -> Response:
    f = access.get_field(db, user, field_id, include_archived=True)
    return Response(json.dumps(exports.boundary_feature(f)), media_type="application/geo+json")


# ----------------------------------------------------------------------------- seasons


@router.get("/fields/{field_id}/seasons", response_model=list[SeasonOut])
def list_seasons(field_id: FieldId, user: CurrentUser, db: DbDep) -> list[SeasonOut]:
    f = access.get_field(db, user, field_id, include_archived=True)
    return [fields.season_out(s) for s in f.seasons]  # type: ignore[misc]


@router.post("/fields/{field_id}/seasons", response_model=SeasonOut, status_code=status.HTTP_201_CREATED)
def create_season(field_id: FieldId, body: SeasonCreate, user: CurrentUser, db: DbDep) -> SeasonOut:
    s = fields.create_season(db, user, access.get_field(db, user, field_id), body)
    db.commit()
    return fields.season_out(s)  # type: ignore[return-value]


@router.patch("/seasons/{season_id}", response_model=SeasonOut)
def patch_season(season_id: str, body: SeasonPatch, user: CurrentUser, db: DbDep) -> SeasonOut:
    s = db.get(FieldSeason, season_id)
    if s is None or not access.can_see_field(user, s.field_id):
        raise NotFound(f"Season {season_id} not found")
    s = fields.patch_season(db, user, s, body)
    db.commit()
    return fields.season_out(s)  # type: ignore[return-value]


# ----------------------------------------------------------------------------- grid


def _grid_args(db, size: int | None, threshold: float | None) -> tuple[int, float]:
    st = get_station(db)
    return analysis.check_grid(size or st.default_grid_m), analysis.check_threshold(threshold if threshold is not None else st.threshold_pct)


@router.get("/fields/{field_id}/grid", summary="The spray grid (columnar encoding; gzip on the wire)")
def get_grid(field_id: FieldId, user: CurrentUser, db: DbDep, size: SizeQ = None, threshold: ThrQ = None,
             survey: RoleQ = "PRE") -> Response:
    f = access.get_field(db, user, field_id)
    g_m, thr = _grid_args(db, size, threshold)
    _, s, surface = analysis.require_pre(db, f, SurveyRole(survey))
    grid = analysis.grid(surface, g_m, thr)
    # Serialised directly: the generic encoder is slow on ~35k-element arrays.
    body = {"fieldId": f.id, "surveyId": s.id, "survey": survey, **grid.compact()}
    return Response(json.dumps(body, separators=(",", ":")), media_type="application/json")


@router.get("/fields/{field_id}/grid/stats", response_model=GridStats, summary="Grid statistics only (for a threshold slider)")
def grid_stats(field_id: FieldId, user: CurrentUser, db: DbDep, size: SizeQ = None, threshold: ThrQ = None,
               survey: RoleQ = "PRE") -> GridStats:
    f = access.get_field(db, user, field_id)
    g_m, thr = _grid_args(db, size, threshold)
    _, _, surface = analysis.require_pre(db, f, SurveyRole(survey))
    grid = analysis.grid(surface, g_m, thr)
    return GridStats(**grid.stats())


@router.get("/fields/{field_id}/grid/compare", response_model=GridCompare,
            summary="1 m vs 2 m vs 5 m: coarser cells always spray more area, and that trade is shown")
def grid_compare(field_id: FieldId, user: CurrentUser, db: DbDep, threshold: ThrQ = None) -> GridCompare:
    f = access.get_field(db, user, field_id)
    _, thr = _grid_args(db, None, threshold)
    _, s, surface = analysis.require_pre(db, f)
    stats = [GridStats(**analysis.grid(surface, m, thr).stats()) for m in (1, 2, 5)]
    return GridCompare(field_id=f.id, threshold_pct=thr, survey_id=s.id, grids=stats,
                       note="A cell is sprayed when any part of it crosses the threshold, so a coarser grid flags more area.")


@router.get("/fields/{field_id}/grid/cell", response_model=CellOut, summary="Inspect the cell under a point (local metres)")
def grid_cell(field_id: FieldId, user: CurrentUser, db: DbDep, x: float, y: float, size: SizeQ = None,
              threshold: ThrQ = None, survey: RoleQ = "PRE") -> CellOut:
    f = access.get_field(db, user, field_id)
    g_m, thr = _grid_args(db, size, threshold)
    _, _, surface = analysis.require_pre(db, f, SurveyRole(survey))
    grid = analysis.grid(surface, g_m, thr)
    at = grid.cell_at(x, y)
    if at is None:
        raise NotFound("That point is outside the field's grid")
    return CellOut(**grid.cell_info(*at))


# ----------------------------------------------------------------------------- zones & route


@router.get("/fields/{field_id}/zones", response_model=list[ZoneOut], summary="Published zones with their observed state")
def list_zones(field_id: FieldId, user: CurrentUser, db: DbDep,
               include_inactive: Annotated[bool, Query(alias="includeInactive")] = False,
               geometry: bool = True) -> list[ZoneOut]:
    f = access.get_field(db, user, field_id)
    season = analysis.current_season(db, f)
    rows = analysis.active_zones(db, season)
    if include_inactive and season is not None:
        rows = list(db.scalars(select(TreatmentZone).where(TreatmentZone.field_season_id == season.id)
                               .order_by(TreatmentZone.active.desc(), TreatmentZone.route_order)).all())
    return [zones.zone_out(z, geometry) for z in rows]


@router.get("/fields/{field_id}/zones/preview", response_model=list[ZonePreview],
            summary="Zones at an unpublished threshold (the dashboard's slider)")
def preview_zones(field_id: FieldId, user: CurrentUser, db: DbDep, threshold: ThrQ = None,
                  geometry: bool = False) -> list[ZonePreview]:
    f = access.get_field(db, user, field_id)
    _, thr = _grid_args(db, None, threshold)
    ctx, _, surface = analysis.require_pre(db, f)
    prior = analysis.prior_zones(db, ctx.season) if ctx.season else ()
    zs = analysis.zones(surface, analysis.gate_of(f), thr, prior)
    return [ZonePreview(code=f"Z-{z.letter}", label=z.label, letter=z.letter, severity=z.severity, dominant_class=z.dominant_class,
                        area_sqm=z.area_sqm, cell_count=z.cell_count, cx=z.cx, cy=z.cy, radius_m=z.radius_m,
                        distance_m=z.distance_m, route_order=z.route_order, mean_infest_pct=z.mean_infest_pct,
                        geometry=[[[list(p) for p in r] for r in poly] for poly in z.polygons] if geometry else None) for z in zs]


@router.post("/fields/{field_id}/zones/{zone}/state", response_model=ZoneOut,
             summary="Observation from the phone: routed, treated, or undo back to flagged")
def set_zone_state(field_id: FieldId, zone: str, body: ZoneStateIn, user: CurrentUser, db: DbDep,
                   device: OptionalDevice) -> ZoneOut:
    f = access.get_field(db, user, field_id)
    z = zones.find_zone(db, f, zone)
    zones.set_state(db, user, f, z, ZoneState(body.state), at=body.at, device_id=device.id if device else None)
    db.commit()
    return zones.zone_out(z)


@router.post("/fields/{field_id}/zones/route-all", response_model=list[ZoneOut], summary="Put every flagged zone on the route")
def route_all(field_id: FieldId, user: CurrentUser, db: DbDep, device: OptionalDevice) -> list[ZoneOut]:
    f = access.get_field(db, user, field_id)
    rows = zones.route_all(db, user, f, device_id=device.id if device else None)
    db.commit()
    return [zones.zone_out(z, False) for z in rows]


@router.get("/fields/{field_id}/route", response_model=RouteOut,
            summary="Nearest-first route through untreated zones, from the gate or from (fromX, fromY)")
def get_route(field_id: FieldId, user: CurrentUser, db: DbDep,
              from_x: Annotated[float | None, Query(alias="fromX")] = None,
              from_y: Annotated[float | None, Query(alias="fromY")] = None,
              include_done: Annotated[bool, Query(alias="includeDone")] = False) -> RouteOut:
    f = access.get_field(db, user, field_id)
    if (from_x is None) != (from_y is None):
        raise Invalid("Give both fromX and fromY, or neither")
    start = (from_x, from_y) if from_x is not None and from_y is not None else None
    return zones.route(db, f, start, include_done)


# ----------------------------------------------------------------------------- verification & rotation


@router.get("/fields/{field_id}/verification", response_model=VerificationPreview,
            summary="Per-zone control, follow-up flight against pre-treatment")
def get_verification(field_id: FieldId, user: CurrentUser, db: DbDep,
                     role: Literal["PLUS_14D", "PLUS_28D"] = "PLUS_14D") -> VerificationPreview:
    f = access.get_field(db, user, field_id)
    return verification.preview(db, f, role)


@router.post("/fields/{field_id}/verification", response_model=VerificationOut, status_code=status.HTTP_201_CREATED)
def save_verification(field_id: FieldId, body: VerificationSave, user: CurrentUser, db: DbDep, device: OptionalDevice,
                      response: Response) -> VerificationOut:
    f = access.get_field(db, user, field_id)
    v, created = verification.save(db, user, f, body.role, client_id=body.client_id, at=body.at,
                                   device_id=device.id if device else None)
    db.commit()
    if not created:
        response.status_code = status.HTTP_200_OK
    return verification.verification_out(v)


@router.get("/fields/{field_id}/verifications", response_model=list[VerificationOut], summary="Saved verifications, all seasons")
def list_verifications(field_id: FieldId, user: CurrentUser, db: DbDep) -> list[VerificationOut]:
    f = access.get_field(db, user, field_id, include_archived=True)
    rows = db.scalars(select(Verification).where(Verification.field_id == f.id).order_by(Verification.saved_at.desc())).all()
    return [verification.verification_out(v) for v in rows]


@router.get("/fields/{field_id}/rotation", response_model=RotationOut, summary="Mode-of-action history and resistance risk")
def get_rotation(field_id: FieldId, user: CurrentUser, db: DbDep) -> RotationOut:
    return treatments.rotation(db, access.get_field(db, user, field_id, include_archived=True))
