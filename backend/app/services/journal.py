"""The two ledgers: the audit log (who changed a definition) and the sync change log
(what moved between a phone and the station, and which ownership rule decided it)."""

from __future__ import annotations

import json
from datetime import datetime

from sqlalchemy.orm import Session

from app.core import clock
from app.models import AuditEntry, ChangeLogEntry, User
from app.services import ids

#: Entities whose truth lives on the phone. Everything else is a dashboard definition.
MOBILE_OWNED = frozenset({"leaf_scan", "treatment", "treatment_zone", "verification", "quadrat", "abstention", "field"})


def audit(
    db: Session,
    who: User | str | None,
    action: str,
    detail: str,
    *,
    entity: str | None = None,
    entity_id: str | None = None,
    at: datetime | None = None,
) -> AuditEntry:
    if isinstance(who, User):
        user_id, name = who.id, who.name
    else:
        user_id, name = None, who or "System"
    row = AuditEntry(
        id=ids.next_id(db, "audit", AuditEntry),
        at=at or clock.now(),
        user_id=user_id,
        who=name,
        action=action,
        detail=detail,
        entity=entity,
        entity_id=entity_id,
    )
    db.add(row)
    return row


def change(
    db: Session,
    *,
    entity: str,
    op: str,
    summary: str,
    entity_id: str | None = None,
    user: User | None = None,
    device_id: str | None = None,
    client_seq: int | None = None,
    at: datetime | None = None,
    payload: object = None,
    status: str = "applied",
    reason: str | None = None,
    owned_by_mobile: bool | None = None,
) -> ChangeLogEntry:
    size = len(json.dumps(payload, default=str)) if payload is not None else 0
    row = ChangeLogEntry(
        id=ids.next_id(db, "changelog", ChangeLogEntry),
        seq=ids.next_value(db, "seq"),
        device_id=device_id,
        client_seq=client_seq,
        user_id=user.id if user else None,
        entity=entity,
        op=op,
        entity_id=entity_id,
        summary=summary[:255],
        at=at or clock.now(),
        received_at=clock.now(),
        owned_by_mobile=(entity in MOBILE_OWNED) if owned_by_mobile is None else owned_by_mobile,
        bytes=size,
        status=status,
        reason=reason,
    )
    db.add(row)
    return row
