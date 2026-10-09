from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Query, status
from sqlalchemy import func, select

from app.api.deps import CurrentUser, DbDep, OptionalDevice, PagingDep, RequiredDevice
from app.models import ChangeLogEntry, Device
from app.schemas.common import Page
from app.schemas.system import ChangeOut, DeviceOut, DeviceRegister, Heartbeat, SyncPullOut, SyncPush, SyncPushOut
from app.services import access, devices, sync

router = APIRouter(tags=["devices & sync"])


@router.get("/devices", response_model=list[DeviceOut], summary="Handsets (all for analysts; your own for operators)")
def list_devices(user: CurrentUser, db: DbDep) -> list[DeviceOut]:
    return [devices.device_out(db, d) for d in devices.list_devices(db, user)]


@router.post("/devices/register", response_model=DeviceOut, status_code=status.HTTP_201_CREATED,
             summary="Register this handset (idempotent on installId)")
def register(body: DeviceRegister, user: CurrentUser, db: DbDep) -> DeviceOut:
    d = devices.register_device(db, user, body)
    db.commit()
    return devices.device_out(db, d)


@router.post("/devices/{device_id}/heartbeat", response_model=DeviceOut, summary="Battery, storage and pending changes")
def heartbeat(device_id: str, body: Heartbeat, user: CurrentUser, db: DbDep) -> DeviceOut:
    d = devices.heartbeat(db, user, devices.get_device(db, user, device_id), body)
    db.commit()
    return devices.device_out(db, d)


@router.post("/devices/{device_id}/revoke", response_model=DeviceOut, summary="Lost handset: end its sessions (admin)")
def revoke(device_id: str, user: CurrentUser, db: DbDep) -> DeviceOut:
    d = devices.set_revoked(db, user, devices.get_device(db, user, device_id), True)
    db.commit()
    return devices.device_out(db, d)


@router.post("/devices/{device_id}/restore", response_model=DeviceOut)
def restore(device_id: str, user: CurrentUser, db: DbDep) -> DeviceOut:
    d = devices.set_revoked(db, user, devices.get_device(db, user, device_id), False)
    db.commit()
    return devices.device_out(db, d)


@router.post("/sync/push", response_model=SyncPushOut,
             summary="Send the phone's pending changes; each is applied, de-duplicated or rejected by ownership")
def push(body: SyncPush, user: CurrentUser, db: DbDep, device: RequiredDevice) -> SyncPushOut:
    out = sync.push(db, user, device, body)
    db.commit()
    return out


@router.get("/sync/pull", response_model=SyncPullOut,
            summary="Everything changed since the cursor (omit `since` for a full snapshot)")
def pull(user: CurrentUser, db: DbDep, device: OptionalDevice, since: datetime | None = None) -> SyncPullOut:
    out = sync.pull(db, user, since, device)
    db.commit()
    return out


@router.get("/sync/changes", response_model=Page[ChangeOut], summary="The sync ledger, newest first")
def changes(
    user: CurrentUser, db: DbDep, paging: PagingDep,
    device_id: Annotated[str | None, Query(alias="deviceId")] = None,
    entity: str | None = None,
    status_: Annotated[Literal["applied", "duplicate", "rejected"] | None, Query(alias="status")] = None,
    owner: Literal["phone", "dashboard"] | None = None,
) -> Page[ChangeOut]:
    q = select(ChangeLogEntry)
    if not access.is_decider(user):
        mine = [d.id for d in db.scalars(select(Device).where(Device.user_id == user.id))]
        q = q.where(ChangeLogEntry.device_id.in_(mine or ["__none__"]))
    if device_id:
        q = q.where(ChangeLogEntry.device_id == device_id)
    if entity:
        q = q.where(ChangeLogEntry.entity == entity)
    if status_:
        q = q.where(ChangeLogEntry.status == status_)
    if owner:
        q = q.where(ChangeLogEntry.owned_by_mobile.is_(owner == "phone"))
    total = int(db.scalar(select(func.count()).select_from(q.subquery())) or 0)
    rows = db.scalars(q.order_by(ChangeLogEntry.seq.desc()).limit(paging.limit).offset(paging.offset)).all()
    names = {d.id: d.name for d in db.scalars(select(Device)).all()}
    items = [
        ChangeOut(id=c.id, seq=c.seq, entity=c.entity, op=c.op, entity_id=c.entity_id, summary=c.summary, at=c.at,
                  received_at=c.received_at, owned_by_mobile=c.owned_by_mobile, owner="phone" if c.owned_by_mobile else "dashboard",
                  bytes=c.bytes, device_id=c.device_id, device_name=names.get(c.device_id or ""), status=c.status,  # type: ignore[arg-type]
                  applied=c.status == "applied", reason=c.reason)
        for c in rows
    ]
    return Page(items=items, total=total, limit=paging.limit, offset=paging.offset)
