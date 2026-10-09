"""Request dependencies: database session, the signed-in user, the calling handset, paging."""

from __future__ import annotations

from datetime import timedelta
from typing import Annotated

from fastapi import Depends, Header, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import Forbidden, Unauthorized
from app.core.security import decode_access_token
from app.db.session import get_db
from app.models import Device, User

DbDep = Annotated[Session, Depends(get_db)]
_bearer = HTTPBearer(auto_error=False, description="Access token from /auth/login")
_ACTIVE_TOUCH = timedelta(minutes=5)


def current_user(
    request: Request, db: DbDep, creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)]
) -> User:
    if creds is None or creds.scheme.lower() != "bearer":
        raise Unauthorized("Sign in required")
    claims = decode_access_token(creds.credentials)
    user = db.get(User, str(claims.get("sub")))
    if user is None or not user.is_active:
        raise Unauthorized("This account is not active", code="account_disabled")
    if user.password_changed_at and int(claims.get("iat", 0)) < int(user.password_changed_at.timestamp()):
        raise Unauthorized("Password changed; sign in again", code="token_revoked")
    now = clock.now()
    if user.last_active_at is None or now - user.last_active_at > _ACTIVE_TOUCH:
        user.last_active_at = now
        db.commit()
    request.state.user = user
    request.state.token_device = claims.get("dev")
    return user


CurrentUser = Annotated[User, Depends(current_user)]


def optional_device(
    request: Request, db: DbDep, user: CurrentUser,
    x_device_id: Annotated[str | None, Header(alias="X-Device-ID", description="The handset's id from registration")] = None,
) -> Device | None:
    device_id = x_device_id or getattr(request.state, "token_device", None)
    if not device_id:
        return None
    d = db.get(Device, device_id)
    if d is None or d.user_id != user.id:
        raise Forbidden("X-Device-ID does not belong to the signed-in operator")
    if d.revoked:
        raise Forbidden("This handset has been revoked by the station")
    return d


OptionalDevice = Annotated[Device | None, Depends(optional_device)]


def required_device(device: OptionalDevice) -> Device:
    if device is None:
        raise Forbidden("This call must come from a registered handset (send X-Device-ID)")
    return device


RequiredDevice = Annotated[Device, Depends(required_device)]


class Paging:
    def __init__(
        self,
        limit: Annotated[int, Query(ge=1, le=500)] = 50,
        offset: Annotated[int, Query(ge=0)] = 0,
    ):
        self.limit = limit
        self.offset = offset


PagingDep = Annotated[Paging, Depends()]


def client_meta(request: Request) -> tuple[str, str | None]:
    ip = request.client.host if request.client else "unknown"
    return ip, request.headers.get("user-agent")
