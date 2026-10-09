"""Treatment records and the rotation / resistance analysis (spec sections 41.1 and 41.2).

A record says what was applied, where and when, with the dose exactly as the operator wrote
it. Nothing here computes or recommends a rate. The rotation view reports which mode of
action has gone onto a field season by season, and what it has cost in control.
"""

from __future__ import annotations

from collections import OrderedDict
from datetime import datetime, timedelta

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import Conflict, Invalid, NotFound
from app.domain.enums import ACCEPTABLE_CONTROL_PCT, HracGroup, WeedClass
from app.models import Field, Product, Treatment, TreatmentZone, User, Verification
from app.schemas.analysis import RotationCheckOut, RotationOut, RotationProduct, RotationSeason
from app.schemas.observations import TreatmentCreate, TreatmentOut, TreatmentResult
from app.services import access, analysis, ids, journal

_ORDINAL = {1: "First", 2: "Second", 3: "Third", 4: "Fourth", 5: "Fifth"}


def treatment_out(t: Treatment, field_name: str | None = None) -> TreatmentOut:
    g = HracGroup(t.hrac_group)
    return TreatmentOut(
        id=t.id, client_id=t.client_id, field_season_id=t.field_season_id, field_id=t.field_id,
        field_name=field_name or t.field_id, zone_labels=list(t.zone_labels or []), applied_at=t.applied_at,
        product=t.product, active_ingredient=t.active_ingredient, hrac_group=g, hrac_display=g.display,
        dose_recorded=t.dose_recorded, dose_unit=t.dose_unit, application_mode=t.application_mode,
        growth_stage=t.growth_stage, operator=t.operator_name, operator_id=t.operator_id, device_id=t.device_id,
        area_acres=t.area_acres, water_litres=t.water_litres, notes=t.notes, rotation_override=t.rotation_override,
        season=clock.season_of(t.applied_at), created_at=t.created_at,
    )


def get_treatment(db: Session, user: User, treatment_id: str) -> Treatment:
    t = db.get(Treatment, treatment_id)
    if t is None or not access.can_see_field(user, t.field_id):
        raise NotFound(f"Treatment {treatment_id} not found")
    return t


def list_treatments(
    db: Session, user: User, *, field_id: str | None = None, hrac: HracGroup | None = None,
    date_from: datetime | None = None, date_to: datetime | None = None, season: str | None = None,
    q: str | None = None, limit: int = 100, offset: int = 0,
) -> tuple[list[Treatment], int]:
    stmt = select(Treatment).join(Field, Field.id == Treatment.field_id).where(Field.archived_at.is_(None))
    scope = access.field_scope(user)
    if scope is not None:
        stmt = stmt.where(Treatment.field_id.in_(scope or {"__none__"}))
    if field_id:
        stmt = stmt.where(Treatment.field_id == field_id)
    if hrac:
        stmt = stmt.where(Treatment.hrac_group == hrac.value)
    if date_from:
        stmt = stmt.where(Treatment.applied_at >= date_from)
    if date_to:
        stmt = stmt.where(Treatment.applied_at <= date_to)
    if q:
        like = f"%{q.strip()}%"
        stmt = stmt.where(or_(Treatment.product.ilike(like), Treatment.active_ingredient.ilike(like), Treatment.notes.ilike(like)))
    rows = list(db.scalars(stmt.order_by(Treatment.applied_at.desc(), Treatment.id.desc())).all())
    if season:
        rows = [t for t in rows if clock.season_of(t.applied_at) == season]
    total = len(rows)
    return rows[offset : offset + limit], total


def find_product(db: Session, product_id: str | None, trade: str | None) -> Product:
    p = None
    if product_id:
        p = db.get(Product, product_id)
    if p is None and trade:
        p = db.scalars(select(Product).where(func.lower(Product.trade) == trade.strip().lower())).first()
    if p is None:
        raise Invalid("Unknown product. Products come from the station's label list; ask the analyst to add it.")
    return p


def _normalise_zone_labels(db: Session, f: Field, season_id: str, labels: list[str]) -> list[str]:
    letters = {z.letter for z in db.scalars(select(TreatmentZone).where(TreatmentZone.field_season_id == season_id))}
    out: list[str] = []
    for raw in labels:
        r = raw.strip()
        letter = r[5:] if r.lower().startswith("zone ") else r[2:] if r.upper().startswith("Z-") else r
        letter = letter.upper()
        if letter not in letters:
            raise Invalid(f"{f.name} has no {('Zone ' + letter) if letter else 'empty zone'} this season")
        label = f"Zone {letter}"
        if label not in out:
            out.append(label)
    return out


def rotation_message(group: HracGroup, streak: int, clash: bool) -> str:
    if not clash:
        return f"{group.display}. A different mode of action from the last application on this field."
    n = streak + 1
    ordinal = _ORDINAL.get(n, f"{n}th")
    return (
        f"{ordinal} application in a row of {group.display}. Repeated exposure to one mode of action "
        "selects for resistant plants; a different group breaks the cycle."
    )


def _prior_desc(db: Session, field_id: str) -> list[Treatment]:
    return list(db.scalars(select(Treatment).where(Treatment.field_id == field_id).order_by(Treatment.applied_at.desc())).all())


def create_treatment(
    db: Session, user: User, data: TreatmentCreate, *, device_id: str | None = None, client_seq: int | None = None,
) -> tuple[TreatmentResult, bool]:
    existing = db.scalar(select(Treatment).where(Treatment.client_id == data.client_id))
    if existing is not None:
        return TreatmentResult(treatment=treatment_out(existing, db.get(Field, existing.field_id).name), rotation_warning=None), False
    f = access.get_field(db, user, data.field_id)
    season = analysis.current_season(db, f)
    if season is None:
        raise Conflict(f"{f.name} has no open season")
    product = find_product(db, data.product_id, data.product)
    labels = _normalise_zone_labels(db, f, season.id, data.zone_labels)
    applied_at = data.applied_at or clock.now()
    if applied_at > clock.now() + timedelta(hours=1):
        raise Invalid("A treatment cannot be recorded in the future")
    prior = _prior_desc(db, f.id)
    group = HracGroup(product.hrac)
    last = HracGroup(prior[0].hrac_group) if prior else None
    streak = 0
    for t in prior:
        if t.hrac_group != group:
            break
        streak += 1
    clash = last == group
    t = Treatment(
        id=ids.next_id(db, "treatment", Treatment), client_id=data.client_id, field_season_id=season.id, field_id=f.id,
        zone_labels=labels, applied_at=applied_at, product=product.trade, active_ingredient=product.active,
        hrac_group=group.value, dose_recorded=data.dose_recorded, dose_unit=data.dose_unit,
        application_mode=data.application_mode.strip(), growth_stage=data.growth_stage.strip(), operator_id=user.id,
        operator_name=user.name, device_id=device_id, area_acres=round(data.area_acres, 4),
        water_litres=data.water_litres, notes=data.notes.strip(), rotation_override=data.rotation_override and clash,
    )
    db.add(t)
    db.flush()
    journal.change(
        db, entity="treatment", op="create", entity_id=t.id, user=user, device_id=device_id, client_seq=client_seq,
        at=applied_at, payload=data.model_dump(mode="json"),
        summary=f"{t.product} · {len(labels)} {'zone' if len(labels) == 1 else 'zones'} · {f.name}",
    )
    return TreatmentResult(treatment=treatment_out(t, f.name), rotation_warning=rotation_message(group, streak, True) if clash else None), True


# ----------------------------------------------------------------------------- rotation


def _rotation_product(p: Product) -> RotationProduct:
    g = HracGroup(p.hrac)
    return RotationProduct(id=p.id, trade=p.trade, active=p.active, hrac=g, hrac_display=g.display,
                           target=p.target, crop=p.crop, formulation=p.formulation)  # type: ignore[arg-type]


def _alternatives(db: Session, exclude: HracGroup | None, classes: set[str]) -> list[RotationProduct]:
    rows = db.scalars(select(Product).where(Product.registered.is_(True), Product.crop == "Wheat")).all()
    alts = [p for p in rows if (exclude is None or p.hrac != exclude.value) and (not classes or p.target in classes)]
    alts.sort(key=lambda p: (int(HracGroup(p.hrac).code), p.trade))
    return [_rotation_product(p) for p in alts]


def rotation(db: Session, f: Field) -> RotationOut:
    history = list(db.scalars(select(Treatment).where(Treatment.field_id == f.id).order_by(Treatment.applied_at)).all())
    seasons: OrderedDict[str, list[Treatment]] = OrderedDict()
    for t in history:
        seasons.setdefault(clock.season_of(t.applied_at), []).append(t)

    control: dict[str, int] = {}
    for v in db.scalars(select(Verification).where(Verification.field_id == f.id).order_by(Verification.saved_at)).all():
        if v.worst_efficacy_pct is not None:
            control[clock.season_of(v.saved_at)] = v.worst_efficacy_pct  # latest flight of the season wins

    latest = HracGroup(seasons[next(reversed(seasons))][0].hrac_group) if seasons else None
    streak = 0
    for key in reversed(seasons):
        if latest is not None and any(t.hrac_group == latest for t in seasons[key]):
            streak += 1
        else:
            break
    rows: list[RotationSeason] = []
    trend: list[dict] = []
    for key, items in seasons.items():
        groups = list(OrderedDict.fromkeys(HracGroup(t.hrac_group) for t in items))
        rows.append(RotationSeason(season=key, hrac_groups=groups, hrac_display=[g.display for g in groups],
                                   treatments=[t.id for t in items], control_pct=control.get(key)))
        if key in control:
            trend.append({"season": key, "hrac": groups[0].value, "controlPct": control[key]})
    last = trend[-1]["controlPct"] if trend else None
    risk = "High" if streak >= 3 and (last if last is not None else 100) < ACCEPTABLE_CONTROL_PCT else "Watch" if streak >= 2 else "Low"

    warning = None
    if latest is not None and streak >= 2:
        ordinal = _ORDINAL.get(streak, f"{streak}th")
        same = [p["controlPct"] for p in trend if p["hrac"] == latest.value]
        fall = " → ".join(f"{c}%" for c in same)
        warning = f"{ordinal} season in a row on {latest.display}."
        if len(same) >= 2 and same[-1] < same[0]:
            warning += (
                f" Control has fallen {fall}. That is the signature of a resistant population being selected by "
                "repeated exposure to the same mode of action, not a spraying mistake."
            )
        warning += " Rotate to a different group next application."

    season = analysis.current_season(db, f)
    classes = {z.dominant_class for z in analysis.active_zones(db, season)} - {WeedClass.CROP.value}
    if seasons:
        y = int(next(reversed(seasons))[:4]) + 1
        next_season = f"{y}-{(y + 1) % 100:02d}"
    else:
        next_season = clock.season_of(clock.now())
    return RotationOut(
        field_id=f.id, seasons=rows, trend=trend, latest_group=latest, latest_group_display=latest.display if latest else None,
        streak=streak, risk=risk, acceptable_control_pct=ACCEPTABLE_CONTROL_PCT, warning=warning,  # type: ignore[arg-type]
        used_groups=list(OrderedDict.fromkeys(HracGroup(t.hrac_group) for t in history)),
        alternatives=_alternatives(db, latest, classes), next_season=next_season,
    )


def rotation_check(db: Session, f: Field, product_id: str | None, trade: str | None) -> RotationCheckOut:
    """The phone's check before a record is written: is this the same group as last time?"""
    p = find_product(db, product_id, trade)
    group = HracGroup(p.hrac)
    prior = _prior_desc(db, f.id)
    last = HracGroup(prior[0].hrac_group) if prior else None
    streak = 0
    for t in prior:
        if t.hrac_group != group:
            break
        streak += 1
    clash = last == group
    season = analysis.current_season(db, f)
    classes = {z.dominant_class for z in analysis.active_zones(db, season)} - {WeedClass.CROP.value}
    return RotationCheckOut(
        field_id=f.id, product=p.trade, hrac=group, hrac_display=group.display, clash=clash, streak=streak,
        last_group=last, message=rotation_message(group, streak, clash),
        alternatives=_alternatives(db, group, classes or {p.target}) if clash else [],
    )

