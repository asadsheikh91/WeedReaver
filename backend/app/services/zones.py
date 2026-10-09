"""Zone observations from the phone, and the spray route."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.analysis.zones import route_legs
from app.core import clock
from app.core.errors import Conflict, NotFound
from app.domain import geo
from app.domain.enums import ZONE_STATE_LABEL, ZoneState
from app.models import Field, TreatmentZone, User
from app.schemas.analysis import RouteLeg, RouteOut, ZoneOut
from app.services import analysis, journal


def zone_out(z: TreatmentZone, with_geometry: bool = True) -> ZoneOut:
    return ZoneOut(
        id=z.id, code=z.code, label=z.label, letter=z.letter, field_id=z.field_id, field_season_id=z.field_season_id,
        survey_id=z.survey_id, severity=z.severity, dominant_class=z.dominant_class,  # type: ignore[arg-type]
        area_sqm=z.area_sqm, cell_count=z.cell_count, cx=z.cx, cy=z.cy, radius_m=z.radius_m,
        distance_m=z.distance_m, route_order=z.route_order, mean_infest_pct=z.mean_infest_pct,
        threshold_pct=z.threshold_pct, state=z.state, state_changed_at=z.state_changed_at,  # type: ignore[arg-type]
        treated_at=z.treated_at, efficacy_pct=z.efficacy_pct, active=z.active,
        geometry=z.geometry if with_geometry else None, updated_at=z.updated_at,
    )


def find_zone(db: Session, field: Field, ref: str, *, include_inactive: bool = False) -> TreatmentZone:
    """A zone by letter ("A"), code ("Z-A"), label ("Zone A") or id, in the current season."""
    season = analysis.current_season(db, field)
    if season is None:
        raise NotFound(f"{field.name} has no current season")
    r = ref.strip()
    letter = r[2:] if r.upper().startswith("Z-") else r[5:] if r.lower().startswith("zone ") else r
    q = select(TreatmentZone).where(TreatmentZone.field_season_id == season.id)
    z = db.scalars(q.where(TreatmentZone.letter == letter.upper())).first() or db.scalars(q.where(TreatmentZone.id == r)).first()
    if z is None or (not z.active and not include_inactive):
        raise NotFound(f"Zone {ref} not found on {field.name}")
    return z


def set_state(
    db: Session, user: User, field: Field, z: TreatmentZone, state: ZoneState, *, at: datetime | None = None,
    device_id: str | None = None, client_seq: int | None = None, log: bool = True,
) -> TreatmentZone:
    """Mobile wins on observations: the phone was there. RESURVEYED is set only by a saved
    verification, never directly."""
    if state == ZoneState.RESURVEYED:
        raise Conflict("A zone becomes re-surveyed when a verification is saved")
    when = at or clock.now()
    previous = z.state
    if previous == state:
        return z
    z.state = state
    z.state_changed_at = when
    if state == ZoneState.TREATED:
        z.treated_at = when
        z.treated_by = user.id
    elif previous in (ZoneState.TREATED, ZoneState.RESURVEYED):
        # Undo of "mark treated" (the phone's slide-to-confirm has an undo).
        z.treated_at = None
        z.treated_by = None
        z.efficacy_pct = None
    z.updated_at = clock.now()
    if log:
        journal.change(
            db, entity="treatment_zone", op="state", entity_id=f"{field.id}/{z.letter}", user=user, device_id=device_id,
            client_seq=client_seq, at=when, summary=f"{z.label} marked {ZONE_STATE_LABEL[state].lower()} · {field.name}",
            payload={"state": state.value},
        )
    return z


def route_all(
    db: Session, user: User, field: Field, *, device_id: str | None = None, client_seq: int | None = None
) -> list[TreatmentZone]:
    season = analysis.current_season(db, field)
    zones = analysis.active_zones(db, season)
    flagged = [z for z in zones if z.state == ZoneState.FLAGGED]
    if not flagged:
        return zones
    now = clock.now()
    for z in flagged:
        z.state = ZoneState.ROUTED
        z.state_changed_at = now
        z.updated_at = now
    journal.change(db, entity="treatment_zone", op="route", entity_id=field.id, user=user, device_id=device_id,
                   client_seq=client_seq, summary=f"Spray route created · {field.name}", payload=[z.letter for z in flagged])
    return zones


def route(db: Session, field: Field, start: geo.Pt | None = None, include_done: bool = False) -> RouteOut:
    """Nearest-first through the zones still to treat, from the gate or from where the operator
    stands (so a route resumes after an interruption)."""
    season = analysis.current_season(db, field)
    zones = analysis.active_zones(db, season)
    todo = [z for z in zones if include_done or not ZoneState(z.state).done]
    origin = start or analysis.gate_of(field)
    legs = route_legs([(z.letter, z.cx, z.cy) for z in todo], origin)
    by_letter = {z.letter: z for z in todo}
    out = [
        RouteLeg(**leg, label=f"Zone {leg['letter']}", zone_id=by_letter[leg["letter"]].id, state=by_letter[leg["letter"]].state)
        for leg in legs
    ]
    return RouteOut(
        field_id=field.id, start={"x": origin[0], "y": origin[1]}, from_gate=start is None,  # type: ignore[arg-type]
        legs=out, total_m=round(sum(leg.distance_m for leg in out), 1), remaining=sum(1 for z in zones if not ZoneState(z.state).done),
    )
