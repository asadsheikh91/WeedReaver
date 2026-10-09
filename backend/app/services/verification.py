"""Verification: the follow-up flight compared with the pre-treatment survey, zone by zone."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.analysis.efficacy import chemical_saved_fraction, field_delta_pct, zone_efficacy
from app.core import clock
from app.core.errors import Conflict
from app.domain.enums import ACCEPTABLE_CONTROL_PCT, SURVEY_ROLE_META, SurveyRole, SurveyStatus, ZoneState
from app.models import Field, User, Verification
from app.schemas.analysis import VerificationOut, VerificationPreview, ZoneEfficacyOut
from app.services import analysis, ids, journal
from app.services.station import get_station

CAVEAT = (
    "Spot spraying the prescription instead of the whole field uses less herbicide at equal control; that saving is "
    "measured against a blanket-spray baseline. It is not a yield gain: spatial targeting is close to free in yield "
    "terms, and the yield claim is measured separately, against a different baseline."
)


def verification_out(v: Verification, names: dict[str, str] | None = None) -> VerificationOut:
    return VerificationOut(
        id=v.id, field_id=v.field_id, field_season_id=v.field_season_id, role=v.role, pre_survey_id=v.pre_survey_id,
        follow_survey_id=v.follow_survey_id, saved_at=v.saved_at,
        saved_by=(names or {}).get(v.saved_by or "", v.saved_by), results=v.results or [],
        worst_efficacy_pct=v.worst_efficacy_pct, field_delta_pct=v.field_delta_pct,
        chemical_saved_pct=v.chemical_saved_pct, historical=v.historical,
    )


def _saved(db: Session, season_id: str | None, role: str) -> Verification | None:
    if season_id is None:
        return None
    return db.scalars(
        select(Verification)
        .where(Verification.field_season_id == season_id, Verification.role == role)
        .order_by(Verification.saved_at.desc())
    ).first()


def _compute(db: Session, f: Field, role: str):
    ctx = analysis.context(db, f)
    pre = analysis.surface_for_role(db, ctx, SurveyRole.PRE)
    follow = ctx.by_role(role)
    reason = None
    if pre is None:
        reason = "Verification compares two flights, so it needs a processed pre-treatment survey first."
    elif follow is None:
        reason = f"No {SURVEY_ROLE_META[SurveyRole(role)]['label'].lower()} flight is planned. Schedule one on the Flights page."
    elif follow.status != SurveyStatus.READY:
        reason = {
            SurveyStatus.SCHEDULED: "The follow-up flight has not been flown yet.",
            SurveyStatus.QUEUED: "The follow-up flight is queued for processing.",
            SurveyStatus.PROCESSING: "The comparison appears the moment the orthomosaic is ready.",
            SurveyStatus.FAILED: "The follow-up flight failed processing; re-upload it.",
        }.get(SurveyStatus(follow.status), "The follow-up flight is not ready.")
    post_surface = analysis.load_surface(db, follow) if follow is not None and reason is None else None
    if reason is None and post_surface is None:
        reason = "The follow-up flight has no segmentation output."
    return ctx, pre, follow, post_surface, reason


def preview(db: Session, f: Field, role: str = SurveyRole.PLUS_14D) -> VerificationPreview:
    ctx, pre, follow, post_surface, reason = _compute(db, f, role)
    saved = _saved(db, ctx.season.id if ctx.season else None, role)
    base = dict(
        field_id=f.id, role=role, pre_survey_id=pre[0].id if pre else None,
        follow_survey_id=follow.id if follow else None, follow_status=follow.status if follow else None,
        follow_progress=follow.progress if follow else None, follow_flown_at=follow.flown_at if follow else None,
        saved=verification_out(saved) if saved else None, caveat=CAVEAT,
    )
    if reason is not None:
        return VerificationPreview(**base, ready=False, reason=reason, zones=[], worst=None, weak_count=0,
                                   field_delta_pct=None, chemical_saved_fraction=None, before_flagged=None, after_flagged=None)
    assert pre is not None and post_surface is not None
    station = get_station(db)
    results = []
    for z in analysis.active_zones(db, ctx.season):
        e = zone_efficacy(z.letter, [[[tuple(p) for p in ring] for ring in poly] for poly in z.geometry], pre[1], post_surface)
        if e is not None:
            results.append(ZoneEfficacyOut(**e.to_json()))
    before = analysis.grid(pre[1], station.default_grid_m, station.threshold_pct)
    after = analysis.grid(post_surface, station.default_grid_m, station.threshold_pct)
    worst = min(results, key=lambda r: r.efficacy_pct) if results else None
    return VerificationPreview(
        **base, ready=True, reason=None, zones=results, worst=worst,
        weak_count=sum(1 for r in results if r.efficacy_pct < ACCEPTABLE_CONTROL_PCT),
        field_delta_pct=field_delta_pct(before, after), chemical_saved_fraction=chemical_saved_fraction(before),
        before_flagged=before.flagged, after_flagged=after.flagged,
    )


def save(
    db: Session, user: User, f: Field, role: str, *, client_id: str | None = None, at=None,
    device_id: str | None = None, client_seq: int | None = None,
) -> tuple[Verification, bool]:
    if client_id:
        existing = db.scalar(select(Verification).where(Verification.client_id == client_id))
        if existing is not None:
            return existing, False
    p = preview(db, f, role)
    if not p.ready:
        raise Conflict(p.reason or "Verification is not ready")
    if not p.zones:
        raise Conflict("There are no zones to verify on this field")
    ctx = analysis.context(db, f)
    assert ctx.season is not None
    v = Verification(
        id=ids.next_id(db, "verification", Verification), client_id=client_id, field_season_id=ctx.season.id,
        field_id=f.id, role=role, pre_survey_id=p.pre_survey_id, follow_survey_id=p.follow_survey_id,
        saved_at=at or clock.now(), saved_by=user.id, device_id=device_id,
        results=[z.model_dump(by_alias=True) for z in p.zones],
        worst_efficacy_pct=p.worst.efficacy_pct if p.worst else None, field_delta_pct=p.field_delta_pct,
        chemical_saved_pct=round(p.chemical_saved_fraction * 100, 2) if p.chemical_saved_fraction is not None else None,
    )
    db.add(v)
    by_letter = {z.letter: z.efficacy_pct for z in p.zones}
    now = clock.now()
    for z in analysis.active_zones(db, ctx.season):
        if z.letter in by_letter:
            z.efficacy_pct = by_letter[z.letter]
            if z.state != ZoneState.RESURVEYED:
                z.state = ZoneState.RESURVEYED
                z.state_changed_at = now
            z.updated_at = now
    db.flush()
    journal.change(db, entity="verification", op="create", entity_id=v.id, user=user, device_id=device_id,
                   client_seq=client_seq, at=v.saved_at, summary=f"Per-zone efficacy · {f.name}", payload=v.results)
    return v, True

