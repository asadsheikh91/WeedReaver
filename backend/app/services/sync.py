"""Phone <-> station sync (spec section 43.3).

The phone works offline and keeps a change log with a monotonic client sequence number.

* Push: the phone sends its pending changes. Each one is applied in its own savepoint, so one
  bad change never blocks the rest, and each is recorded in the ledger under
  (device, client_seq), so a retried push is idempotent.
* Pull: the phone asks for everything that changed since its cursor and upserts it.

Conflicts are settled by ownership, never by last-write-wins: the phone wins on observations
(it was physically there), the dashboard wins on definitions (it is the deciding surface). A
change from the phone that tries to alter a definition is rejected with the reason.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import AppError, Invalid, NotFound, OwnershipViolation
from app.domain.enums import ZoneState
from app.models import (
    ChangeLogEntry, Device, Field, FieldSeason, LeafScan, Product, Quadrat, Species, Survey, Treatment, TreatmentZone,
    User, Verification,
)
from app.schemas.fields import FieldCreate
from app.schemas.observations import AnnotateIn, QuadratCreate, ScanCreate, TreatmentCreate
from app.schemas.system import SyncChange, SyncPullOut, SyncPush, SyncPushOut, SyncResult
from app.services import access, catalog, fields, journal, scans, surveys, treatments, verification, zones
from app.services.station import get_station, station_out

PULL_OVERLAP = timedelta(seconds=5)
log = logging.getLogger("weedreaver.sync")


@dataclass
class Applied:
    entity_id: str | None
    created: bool = True


Handler = Callable[[Session, User, Device, SyncChange], Applied]


def _scan_create(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    s, created = scans.create_scan(db, user, ScanCreate.model_validate(ch.payload), device_id=device.id, client_seq=ch.client_seq)
    return Applied(s.id, created)


def _treatment_create(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    r, created = treatments.create_treatment(db, user, TreatmentCreate.model_validate(ch.payload), device_id=device.id,
                                             client_seq=ch.client_seq)
    return Applied(r.treatment.id, created)


def _field_of(db: Session, user: User, payload: dict) -> Field:
    fid = payload.get("fieldId") or payload.get("field_id")
    if not fid:
        raise Invalid("payload.fieldId is required")
    return access.get_field(db, user, str(fid))


def _zone_state(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    f = _field_of(db, user, ch.payload)
    ref = ch.payload.get("zone") or ch.payload.get("letter") or ch.payload.get("zoneId")
    if not ref:
        raise Invalid("payload.zone is required")
    try:
        state = ZoneState(str(ch.payload.get("state")))
    except ValueError as exc:
        raise Invalid("payload.state must be FLAGGED, ROUTED or TREATED") from exc
    z = zones.find_zone(db, f, str(ref), include_inactive=True)
    before = z.state
    zones.set_state(db, user, f, z, state, at=ch.at, device_id=device.id, client_seq=ch.client_seq)
    return Applied(f"{f.id}/{z.letter}", before != z.state)


def _zone_route(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    f = _field_of(db, user, ch.payload)
    zones.route_all(db, user, f, device_id=device.id, client_seq=ch.client_seq)
    return Applied(f.id)


def _verification_create(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    f = _field_of(db, user, ch.payload)
    role = str(ch.payload.get("role") or "PLUS_14D")
    v, created = verification.save(db, user, f, role, client_id=ch.payload.get("clientId"), at=ch.at, device_id=device.id,
                                   client_seq=ch.client_seq)
    return Applied(v.id, created)


def _quadrat_create(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    q, created = catalog.create_quadrat(db, user, QuadratCreate.model_validate(ch.payload), device_id=device.id,
                                        client_seq=ch.client_seq)
    return Applied(q.id, created)


def _annotate(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    p = ch.payload
    s = None
    if p.get("scanId"):
        s = db.get(LeafScan, str(p["scanId"]))
    elif p.get("scanClientId"):
        s = db.scalar(select(LeafScan).where(LeafScan.client_id == str(p["scanClientId"])))
    if s is None or not access.can_see_field(user, s.field_id):
        raise NotFound("Scan not found; push the scan before its annotation")
    scans.annotate(db, user, s, AnnotateIn.model_validate(p), device_id=device.id, client_seq=ch.client_seq)
    return Applied(s.id)


def _field_create(db: Session, user: User, device: Device, ch: SyncChange) -> Applied:
    f, created = fields.create_field(db, user, FieldCreate.model_validate(ch.payload), device_id=device.id,
                                     client_seq=ch.client_seq)
    return Applied(f.id, created)


HANDLERS: dict[tuple[str, str], Handler] = {
    ("leaf_scan", "create"): _scan_create,
    ("treatment", "create"): _treatment_create,
    ("treatment_zone", "state"): _zone_state,
    ("treatment_zone", "route"): _zone_route,
    ("verification", "create"): _verification_create,
    ("quadrat", "create"): _quadrat_create,
    ("abstention", "annotate"): _annotate,
    ("field", "create"): _field_create,
}


def push(db: Session, user: User, device: Device, body: SyncPush) -> SyncPushOut:
    if device.user_id != user.id:
        raise OwnershipViolation("A handset syncs for the operator signed in on it")
    if device.revoked:
        raise OwnershipViolation("This handset has been revoked by the station")
    results: list[SyncResult] = []
    for ch in sorted(body.changes, key=lambda c: c.client_seq):
        prior = db.scalar(select(ChangeLogEntry).where(ChangeLogEntry.device_id == device.id,
                                                       ChangeLogEntry.client_seq == ch.client_seq))
        if prior is not None:
            results.append(SyncResult(client_seq=ch.client_seq, status="duplicate", entity=ch.entity,
                                      entity_id=prior.entity_id, change_id=prior.id, reason=prior.reason))
            continue
        handler = HANDLERS.get((ch.entity, ch.op))
        reason: str | None = None
        applied: Applied | None = None
        sp = db.begin_nested()
        try:
            if handler is None:
                if ch.entity in ("field_season", "survey", "settings", "product") or (ch.entity, ch.op) in (
                    ("field", "update"), ("field", "archive"), ("treatment_zone", "publish"),
                ):
                    raise OwnershipViolation(f"{ch.entity}.{ch.op} changes a definition, which the dashboard owns")
                raise Invalid(f"Unsupported change {ch.entity}.{ch.op}")
            applied = handler(db, user, device, ch)
            db.flush()
            sp.commit()
        except Exception as exc:  # noqa: BLE001 - one bad change must never sink the phone's whole batch
            sp.rollback()
            if isinstance(exc, AppError):
                reason = exc.message
            elif isinstance(exc, ValidationError):
                reason = "Invalid payload: " + "; ".join(f"{'.'.join(map(str, e['loc']))}: {e['msg']}" for e in exc.errors()[:5])
            elif isinstance(exc, (IntegrityError, ValueError)):
                reason = f"Rejected: {type(exc).__name__}"
            else:
                log.exception("Sync change %s.%s from %s failed", ch.entity, ch.op, device.id)
                reason = "Rejected: the station could not apply this change; it has been logged"
        if applied is None:
            entry = journal.change(db, entity=ch.entity, op=ch.op, user=user, device_id=device.id, client_seq=ch.client_seq,
                                   at=ch.at, summary=f"Rejected {ch.entity}.{ch.op}", payload=ch.payload, status="rejected",
                                   reason=reason)
            results.append(SyncResult(client_seq=ch.client_seq, status="rejected", entity=ch.entity, change_id=entry.id, reason=reason))
            continue
        entry = db.scalar(select(ChangeLogEntry).where(ChangeLogEntry.device_id == device.id,
                                                       ChangeLogEntry.client_seq == ch.client_seq))
        if entry is None:  # the service had nothing new to log (an idempotent repeat or a no-op)
            entry = journal.change(db, entity=ch.entity, op=ch.op, entity_id=applied.entity_id, user=user, device_id=device.id,
                                   client_seq=ch.client_seq, at=ch.at, payload=ch.payload,
                                   status="applied" if applied.created else "duplicate",
                                   summary=f"{ch.entity}.{ch.op} · {applied.entity_id or ''} · already recorded".strip())
        results.append(SyncResult(client_seq=ch.client_seq, status="applied" if applied.created else "duplicate",
                                  entity=ch.entity, entity_id=applied.entity_id, change_id=entry.id))
    now = clock.now()
    device.last_sync_at = now
    device.last_seen_at = now
    device.pending_changes = body.pending_after if body.pending_after is not None else 0
    db.flush()
    applied_n = sum(1 for r in results if r.status == "applied")
    if applied_n:
        journal.audit(db, "System", "Phone sync received", f"{applied_n} changes from {device.name}", entity="device", entity_id=device.id)
    return SyncPushOut(results=results, applied=applied_n, duplicates=sum(1 for r in results if r.status == "duplicate"),
                       rejected=sum(1 for r in results if r.status == "rejected"), server_time=now)


def _dump(model) -> dict:
    return model.model_dump(mode="json", by_alias=True)


def pull(db: Session, user: User, since: datetime | None, device: Device | None = None) -> SyncPullOut:
    cursor = clock.now()
    after = (clock.ensure_utc(since) - PULL_OVERLAP) if since else None

    def changed(model):
        q = select(model)
        return q.where(model.updated_at > after) if after else q

    fq = select(Field)
    scope = access.field_scope(user)
    if scope is not None:
        fq = fq.where(Field.id.in_(scope or {"__none__"}))
    all_fields = {f.id: f for f in db.scalars(fq).all()}
    visible = {fid for fid, f in all_fields.items() if f.archived_at is None}
    changed_fields = [f for f in all_fields.values() if after is None or f.updated_at > after]

    def in_scope(rows, attr: str = "field_id"):
        return [r for r in rows if getattr(r, attr) in visible]

    seasons = [s for s in db.scalars(changed(FieldSeason)).all() if s.field_id in visible]
    season_field = {s.id: s.field_id for s in db.scalars(select(FieldSeason)).all()}
    survey_rows = [s for s in db.scalars(changed(Survey)).all() if season_field.get(s.field_season_id) in visible]
    zone_rows = in_scope(db.scalars(changed(TreatmentZone)).all())
    names = {u.id: u.name for u in db.scalars(select(User)).all()}
    scan_rows = in_scope(db.scalars(changed(LeafScan).order_by(LeafScan.updated_at.desc()).limit(1000)).all())
    treat_rows = in_scope(db.scalars(changed(Treatment)).all())
    ver_rows = in_scope(db.scalars(changed(Verification)).all())
    quad_rows = in_scope(db.scalars(changed(Quadrat)).all())

    if device is not None:
        device.last_seen_at = cursor
    return SyncPullOut(
        cursor=cursor,
        full=after is None,
        settings=station_out(get_station(db)),
        fields=[_dump(fields.field_out(f)) for f in changed_fields if f.id in visible],
        seasons=[_dump(fields.season_out(s)) for s in seasons],
        surveys=[_dump(surveys.survey_out(db, s)) for s in survey_rows],
        zones=[_dump(zones.zone_out(z)) for z in zone_rows],
        products=[_dump(catalog.product_out(p)) for p in db.scalars(changed(Product)).all()],
        species=[_dump(catalog.species_out(s)) for s in db.scalars(changed(Species)).all()],
        scans=[_dump(scans.scan_out(db, s, names)) for s in scan_rows],
        treatments=[_dump(treatments.treatment_out(t, all_fields[t.field_id].name)) for t in treat_rows],
        verifications=[_dump(verification.verification_out(v, names)) for v in ver_rows],
        quadrats=[_dump(catalog.quadrat_out(q, all_fields[q.field_id].name)) for q in quad_rows],
        removed_fields=[f.id for f in changed_fields if f.id not in visible] if after else [],
    )
