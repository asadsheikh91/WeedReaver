"""Password hashing, access tokens and opaque one-time tokens."""

from __future__ import annotations

import hashlib
import secrets
from datetime import timedelta
from typing import Any

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from app.core import clock
from app.core.config import get_settings
from app.core.errors import Unauthorized

_hasher = PasswordHasher()

# A real hash of a random string, verified against when the e-mail is unknown so that a
# login for a missing account costs the same time as one for a real account.
_DUMMY_HASH = _hasher.hash(secrets.token_urlsafe(16))

MIN_PASSWORD_LENGTH = 8


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, hashed: str | None) -> bool:
    try:
        return _hasher.verify(hashed or _DUMMY_HASH, password) and hashed is not None
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def needs_rehash(hashed: str) -> bool:
    return _hasher.check_needs_rehash(hashed)


def create_access_token(user_id: str, role: str, *, device_id: str | None = None) -> tuple[str, int]:
    s = get_settings()
    now = clock.now()
    ttl = timedelta(minutes=s.access_token_minutes)
    claims: dict[str, Any] = {
        "sub": user_id,
        "role": role,
        "type": "access",
        "iat": int(now.timestamp()),
        "exp": int((now + ttl).timestamp()),
        "jti": secrets.token_hex(8),
    }
    if device_id:
        claims["dev"] = device_id
    return jwt.encode(claims, s.secret_key, algorithm=s.jwt_algorithm), int(ttl.total_seconds())


def decode_access_token(token: str) -> dict[str, Any]:
    s = get_settings()
    try:
        # The demo clock may run ahead of the real one, so expiry is checked against it.
        claims = jwt.decode(
            token, s.secret_key, algorithms=[s.jwt_algorithm], options={"verify_exp": False, "verify_iat": False}
        )
    except jwt.PyJWTError as exc:
        raise Unauthorized("Invalid access token") from exc
    if claims.get("type") != "access":
        raise Unauthorized("Invalid access token")
    if int(claims.get("exp", 0)) < int(clock.now().timestamp()):
        raise Unauthorized("Access token expired", code="token_expired")
    return claims


def new_opaque_token() -> str:
    return secrets.token_urlsafe(32)


def token_digest(token: str) -> str:
    """Opaque tokens are stored only as a digest; a database leak exposes no usable token."""
    return hashlib.sha256(token.encode()).hexdigest()
