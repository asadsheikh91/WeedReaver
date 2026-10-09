"""Handsets running the field app."""

from __future__ import annotations

from datetime import timedelta

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import Forbidden, NotFound
from app.models import Device, RefreshToken, User
from app.schemas.system import DeviceOut, Heartbeat
from app.services import access, ids, journal
from app.services.station import get_station, version_tuple

ONLINE_WINDOW = timedelta(minutes=5)


def register_device(db: Session, user: User, info) -> Device:  # info: DeviceInfo | DeviceRegister
    """Idempotent on the app's install id. A handset handed to another operator follows them."""
    d = db.scalar(select(Device).where(Device.install_id == info.install_id))
    if d is None:
        d = Device(id=ids.next_id(db, "device", Device), install_id=info.install_id, name=info.name.strip())
        db.add(d)
        journal.audit(db, user, "Device registered", f"{d.name} · {info.model or 'unknown model'}", entity="device", entity_id=d.id)
    elif d.revoked:
        raise Forbidden("This handset has been revoked by the station; ask an administrator")
    d.name = info.name.strip() or d.name
    d.model = info.model or d.model
    d.os = info.os or d.os
    d.app_version = info.app_version or d.app_version
    d.user_id = user.id
    d.last_seen_at = clock.now()
    db.flush()
    return d


def device_out(db: Session, d: Device) -> DeviceOut:
    station = get_station(db)
    latest = version_tuple(station.app_latest_version)
    current = version_tuple(d.app_version)
    online = d.last_seen_at is not None and clock.now() - d.last_seen_at <= ONLINE_WINDOW
    return DeviceOut(
        id=d.id, name=d.name, model=d.model, os=d.os, app_version=d.app_version,
        operator=d.user.name if d.user else None, operator_id=d.user_id,
        operator_code=d.user.operator_code if d.user else None, battery=d.battery, storage_mb=d.storage_mb,
        pending_changes=d.pending_changes, last_seen_at=d.last_seen_at, last_sync_at=d.last_sync_at, online=online,
        revoked=d.revoked, update_available=bool(latest and current and current < latest),
    )


def list_devices(db: Session, user: User) -> list[Device]:
    q = select(Device).order_by(Device.id)
    if not access.is_decider(user):
        q = q.where(Device.user_id == user.id)
    return list(db.scalars(q).all())


def get_device(db: Session, user: User, device_id: str) -> Device:
    d = db.get(Device, device_id)
    if d is None or (not access.is_decider(user) and d.user_id != user.id):
        raise NotFound(f"Device {device_id} not found")
    return d


def heartbeat(db: Session, user: User, d: Device, data: Heartbeat) -> Device:
    if d.user_id != user.id:
        raise Forbidden("A handset reports for its own operator")
    if d.revoked:
        raise Forbidden("This handset has been revoked by the station")
    for k, v in data.model_dump(exclude_unset=True).items():
        if v is not None:
            setattr(d, k, v)
    d.last_seen_at = clock.now()
    return d


def set_revoked(db: Session, actor: User, d: Device, revoked: bool) -> Device:
    """Revoking a lost handset ends its sessions; it can no longer sign in or sync."""
    access.require_admin(actor)
    d.revoked = revoked
    if revoked:
        db.execute(update(RefreshToken).where(RefreshToken.device_id == d.id, RefreshToken.revoked_at.is_(None))
                   .values(revoked_at=clock.now()))
    journal.audit(db, actor, "Device revoked" if revoked else "Device restored", d.name, entity="device", entity_id=d.id)
    return d
