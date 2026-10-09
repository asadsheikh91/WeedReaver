"""Field-level analysis: which survey is current, its surface, the grid, and published zones."""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.analysis import cache
from app.analysis.raster import Grid, rasterise
from app.analysis.surface import PatchSurface, RasterSurface, WeedSurface
from app.analysis.zones import PriorZone, ZoneGeom, compute_zones
from app.core import clock
from app.core.config import get_settings
from app.core.errors import Invalid, NotFound
from app.domain.enums import GRID_SIZES, SurveyRole, SurveyStatus
from app.models import Field, FieldSeason, Survey, SurveySurface, TreatmentZone, User
from app.services import journal
from app.services.station import get_station
from app.services.storage import get_storage


def boundary_of(field: Field) -> list[tuple[float, float]]:
    return [(float(p[0]), float(p[1])) for p in field.boundary]


def gate_of(field: Field) -> tuple[float, float]:
    return float(field.gate[0]), float(field.gate[1])


# ----------------------------------------------------------------------------- seasons & surveys


def current_season(db: Session, field: Field) -> FieldSeason | None:
    station = get_station(db)
    seasons = list(field.seasons)
    for s in seasons:
        if s.season == station.season_label:
            return s
    if not seasons:
        return None
    return max(seasons, key=lambda s: (s.sowing_date or clock.today(), s.created_at))


def season_surveys(db: Session, season: FieldSeason | None) -> list[Survey]:
    if season is None:
        return []
    rows = db.scalars(select(Survey).where(Survey.field_season_id == season.id)).all()
    return sorted(rows, key=lambda s: (s.flown_at, s.id))


def survey_for_role(surveys: list[Survey], role: SurveyRole | str) -> Survey | None:
    """The survey holding a role: a READY one if there is one, else the most recent active,
    else the most recent of any status."""
    of_role = [s for s in surveys if s.role == role]
    if not of_role:
        return None
    for status in (SurveyStatus.READY, SurveyStatus.PROCESSING, SurveyStatus.QUEUED, SurveyStatus.SCHEDULED):
        match = [s for s in of_role if s.status == status]
        if match:
            return max(match, key=lambda s: (s.processed_at or s.flown_at))
    return of_role[-1]


@dataclass
class FieldContext:
    field: Field
    season: FieldSeason | None
    surveys: list[Survey]

    def by_role(self, role: SurveyRole | str) -> Survey | None:
        return survey_for_role(self.surveys, role)

    @property
    def pre(self) -> Survey | None:
        s = self.by_role(SurveyRole.PRE)
        return s if s and s.status == SurveyStatus.READY else None

    @property
    def surveyed(self) -> bool:
        return self.pre is not None


def context(db: Session, field: Field) -> FieldContext:
    season = current_season(db, field)
    return FieldContext(field=field, season=season, surveys=season_surveys(db, season))


# ----------------------------------------------------------------------------- surfaces


def load_surface(db: Session, survey: Survey) -> WeedSurface | None:
    row = db.get(SurveySurface, survey.id)
    if row is None:
        return None
    field = survey.season.field
    boundary = boundary_of(field)
    seed = int((field.landscape or {}).get("seed", 0))
    key = (survey.id, row.updated_at.isoformat(), json.dumps(boundary))

    def make() -> WeedSurface:
        if row.kind == "patches":
            return PatchSurface.from_spec(boundary, row.spec or {})
        if row.kind == "raster" and row.storage_key:
            return RasterSurface.load(get_storage().open(row.storage_key), boundary, seed)
        raise Invalid(f"Survey {survey.id} has an unreadable surface")

    return cache.surfaces().get_or_make(key, make)


def store_surface(db: Session, survey: Survey, surface: WeedSurface, model_version: str | None) -> SurveySurface:
    row = db.get(SurveySurface, survey.id)
    if row is None:
        row = SurveySurface(survey_id=survey.id, kind="patches")
        db.add(row)
    storage = get_storage()
    if isinstance(surface, PatchSurface):
        if row.storage_key:
            storage.delete(row.storage_key)
        row.kind, row.spec, row.storage_key = "patches", surface.to_spec(), None
    elif isinstance(surface, RasterSurface):
        key = f"surfaces/{survey.id}/{uuid.uuid4().hex[:10]}.npz"
        surface.save(storage.path(key))
        if row.storage_key:
            storage.delete(row.storage_key)
        row.kind, row.spec, row.storage_key = "raster", None, key
    else:
        raise Invalid("Segmentation must return a PatchSurface or RasterSurface")
    row.model_version = model_version
    row.updated_at = clock.now()
    db.flush()
    return row


def surface_for_role(db: Session, ctx: FieldContext, role: SurveyRole | str) -> tuple[Survey, WeedSurface] | None:
    s = ctx.by_role(role)
    if s is None or s.status != SurveyStatus.READY:
        return None
    surface = load_surface(db, s)
    return (s, surface) if surface is not None else None


# ----------------------------------------------------------------------------- grids & zones


def check_threshold(threshold_pct: float) -> float:
    s = get_settings()
    if not s.threshold_min_pct <= threshold_pct <= s.threshold_max_pct:
        raise Invalid(f"Threshold must be between {s.threshold_min_pct} and {s.threshold_max_pct} percent")
    return float(threshold_pct)


def check_grid(size: int) -> int:
    if size not in GRID_SIZES:
        raise Invalid("Grid size must be 1, 2 or 5 metres")
    return size


def grid(surface: WeedSurface, size: int, threshold_pct: float) -> Grid:
    return cache.grids().get_or_make((surface.fingerprint, size, round(threshold_pct, 3)), lambda: rasterise(surface, size, threshold_pct))


def zones(surface: WeedSurface, gate: tuple[float, float], threshold_pct: float, prior: tuple[PriorZone, ...] = ()) -> list[ZoneGeom]:
    key = (surface.fingerprint, gate, round(threshold_pct, 3), prior)
    return cache.zones().get_or_make(key, lambda: compute_zones(surface, gate, threshold_pct, prior))


def prior_zones(db: Session, season: FieldSeason) -> tuple[PriorZone, ...]:
    rows = db.scalars(select(TreatmentZone).where(TreatmentZone.field_season_id == season.id)).all()
    return tuple(sorted((PriorZone(z.letter, z.cx, z.cy, z.radius_m) for z in rows), key=lambda p: p.letter))


def require_pre(db: Session, field: Field, role: SurveyRole | str = SurveyRole.PRE) -> tuple[FieldContext, Survey, WeedSurface]:
    ctx = context(db, field)
    got = surface_for_role(db, ctx, role)
    if got is None:
        label = "pre-treatment" if role == SurveyRole.PRE else str(role)
        raise NotFound(f"{field.name} has no processed {label} survey yet")
    return ctx, got[0], got[1]


def publish_zones(db: Session, field: Field, *, actor: User | str | None, threshold_pct: float | None = None, quiet: bool = False) -> list[TreatmentZone]:
    """Compute zones from the current pre-treatment survey and publish them.

    Definition columns are overwritten; the phone's observations (state, when treated,
    efficacy) are kept, matched by letter. Zones no longer found are retired, not deleted.
    """
    ctx = context(db, field)
    if ctx.season is None:
        return []
    got = surface_for_role(db, ctx, SurveyRole.PRE)
    existing = {z.letter: z for z in db.scalars(select(TreatmentZone).where(TreatmentZone.field_season_id == ctx.season.id)).all()}
    if got is None:
        for z in existing.values():
            z.active = False
        return []
    survey, surface = got
    thr = float(threshold_pct if threshold_pct is not None else get_station(db).threshold_pct)
    computed = zones(surface, gate_of(field), thr, prior_zones(db, ctx.season))
    now = clock.now()
    seen: set[str] = set()
    out: list[TreatmentZone] = []
    for zg in computed:
        z = existing.get(zg.letter)
        if z is None:
            z = TreatmentZone(
                id=str(uuid.uuid4()), field_season_id=ctx.season.id, field_id=field.id, letter=zg.letter,
                state="FLAGGED", state_changed_at=now,
            )
            db.add(z)
        z.survey_id = survey.id
        z.severity = zg.severity.value
        z.dominant_class = zg.dominant_class.value
        z.area_sqm = zg.area_sqm
        z.cell_count = zg.cell_count
        z.cx, z.cy = zg.cx, zg.cy
        z.radius_m = round(zg.radius_m, 3)
        z.mean_infest_pct = round(zg.mean_infest_pct, 4)
        z.route_order = zg.route_order
        z.distance_m = zg.distance_m
        z.threshold_pct = thr
        z.geometry = [[[list(p) for p in ring] for ring in poly] for poly in zg.polygons]
        z.active = True
        z.updated_at = now
        seen.add(zg.letter)
        out.append(z)
    for letter, z in existing.items():
        if letter not in seen and z.active:
            z.active = False
            z.updated_at = now
    db.flush()
    if not quiet:
        journal.change(
            db, entity="treatment_zone", op="publish", entity_id=field.id, owned_by_mobile=False,
            summary=f"Zones published · {field.name} · {len(out)} at {thr:g}%", payload=[z.letter for z in out],
        )
        journal.audit(db, actor, "Zones published to phones", f"{field.name} · {len(out)} zones at {thr:g}% threshold",
                      entity="field", entity_id=field.id)
    return out


def active_zones(db: Session, season: FieldSeason | None) -> list[TreatmentZone]:
    if season is None:
        return []
    rows = db.scalars(
        select(TreatmentZone).where(TreatmentZone.field_season_id == season.id, TreatmentZone.active.is_(True))
    ).all()
    return sorted(rows, key=lambda z: (z.route_order, z.letter))
