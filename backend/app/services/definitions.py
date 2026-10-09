"""Changing station definitions: settings and the prescription threshold.

The threshold is the one number that decides how much area is sprayed, so it lives on the
dashboard; publishing it re-zones every surveyed field and phones pick it up on their next
sync (they display it read-only).
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import clock
from app.models import Field, StationSettings, User
from app.schemas.system import StationPatch, ThresholdOut
from app.services import access, analysis, journal
from app.services.station import get_station


def patch_station(db: Session, user: User, data: StationPatch) -> StationSettings:
    access.require_decider(user, "Station settings")
    row = get_station(db)
    changes = {k: v for k, v in data.model_dump(exclude_unset=True).items() if v is not None and getattr(row, k) != v}
    if not changes:
        return row
    for k, v in changes.items():
        setattr(row, k, v)
    row.updated_at = clock.now()
    journal.audit(db, user, "Settings changed", ", ".join(f"{k} → {v}" for k, v in changes.items()), entity="settings")
    journal.change(db, entity="settings", op="update", user=user, owned_by_mobile=False,
                   summary=f"Station settings changed · {', '.join(changes)}", payload=changes)
    return row


def publish_threshold(db: Session, user: User, value: float) -> ThresholdOut:
    access.require_decider(user, "The prescription threshold")
    value = analysis.check_threshold(round(float(value), 1))
    row = get_station(db)
    previous = row.threshold_pct
    now = clock.now()
    row.threshold_pct = value
    row.threshold_published_at = now
    row.threshold_published_by = user.name
    row.updated_at = now
    db.flush()
    rezoned = 0
    zone_count = 0
    for f in db.scalars(select(Field).where(Field.archived_at.is_(None))).all():
        zs = analysis.publish_zones(db, f, actor=user, threshold_pct=value, quiet=True)
        if zs or analysis.context(db, f).surveyed:
            rezoned += 1
            zone_count += len(zs)
    journal.audit(db, user, "Threshold set", f"Prescription threshold {previous:g}% → {value:g}%", entity="settings")
    journal.change(db, entity="settings", op="threshold", user=user, owned_by_mobile=False,
                   summary=f"Prescription threshold {previous:g}% → {value:g}% · {rezoned} fields re-zoned",
                   payload={"thresholdPct": value})
    return ThresholdOut(threshold_pct=value, previous_pct=previous, fields_rezoned=rezoned, zones=zone_count, published_at=now)
