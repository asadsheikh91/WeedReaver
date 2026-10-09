from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import EmailStr, Field, field_validator

from app.core.security import MIN_PASSWORD_LENGTH
from app.domain.enums import Role
from app.schemas.common import ApiModel


class DeviceInfo(ApiModel):
    """Sent by the field app at sign-in so the handset is registered with the station."""

    install_id: str = Field(min_length=8, max_length=64)
    name: str = Field(min_length=1, max_length=120)
    model: str | None = Field(default=None, max_length=120)
    os: str | None = Field(default=None, max_length=60)
    app_version: str | None = Field(default=None, max_length=40)


class LoginIn(ApiModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=256)
    device: DeviceInfo | None = None


class RefreshIn(ApiModel):
    refresh_token: str = Field(min_length=10, max_length=256)


class TokenOut(ApiModel):
    access_token: str
    refresh_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in: int
    user: "UserOut"
    device_id: str | None = None


class UserOut(ApiModel):
    id: str
    email: str
    name: str
    role: Role
    role_label: str
    operator_code: str | None = None
    is_active: bool
    scope_all: bool
    field_ids: list[str] = []
    scope_label: str
    last_active_at: datetime | None = None
    created_at: datetime | None = None


class Preferences(ApiModel):
    """Per-user settings the field app shows on its Settings screen (spec section 43.4)."""

    language: Literal["English", "Urdu", "Punjabi"] = "English"
    use_local_units: bool = True
    voice_prompts: bool = True
    haptic_proximity: bool = True
    keep_screen_on: bool = True
    use_device_camera: bool = True
    grid_size: Literal[1, 2, 5] | None = None
    offline_mode: bool = True
    tiles_cached: bool = True


class PreferencesPatch(ApiModel):
    language: Literal["English", "Urdu", "Punjabi"] | None = None
    use_local_units: bool | None = None
    voice_prompts: bool | None = None
    haptic_proximity: bool | None = None
    keep_screen_on: bool | None = None
    use_device_camera: bool | None = None
    grid_size: Literal[1, 2, 5] | None = None
    offline_mode: bool | None = None
    tiles_cached: bool | None = None


class MeOut(UserOut):
    preferences: Preferences


def _password_rules(v: str) -> str:
    if len(v) < MIN_PASSWORD_LENGTH:
        raise ValueError(f"Password must be at least {MIN_PASSWORD_LENGTH} characters")
    if v.strip() != v or not v.strip():
        raise ValueError("Password must not start or end with spaces")
    return v


class ChangePasswordIn(ApiModel):
    current_password: str
    new_password: str = Field(max_length=256)

    @field_validator("new_password")
    @classmethod
    def _rules(cls, v: str) -> str:
        return _password_rules(v)


class InviteIn(ApiModel):
    email: EmailStr
    role: Role
    name: str | None = Field(default=None, max_length=120)
    field_ids: list[str] | None = None  # None: all fields


class InvitationOut(ApiModel):
    id: str
    email: str
    role: Role
    name: str | None
    field_ids: list[str] | None
    created_at: datetime
    expires_at: datetime
    accepted_at: datetime | None
    revoked_at: datetime | None
    status: Literal["pending", "accepted", "expired", "revoked"]
    #: Returned once, at creation, so it can be delivered when no mail server is configured.
    token: str | None = None
    link: str | None = None


class AcceptInviteIn(ApiModel):
    token: str = Field(min_length=10, max_length=256)
    name: str = Field(min_length=1, max_length=120)
    password: str = Field(max_length=256)
    device: DeviceInfo | None = None

    @field_validator("password")
    @classmethod
    def _rules(cls, v: str) -> str:
        return _password_rules(v)


class ResetRequestIn(ApiModel):
    email: EmailStr


class ResetConfirmIn(ApiModel):
    token: str = Field(min_length=10, max_length=256)
    new_password: str = Field(max_length=256)

    @field_validator("new_password")
    @classmethod
    def _rules(cls, v: str) -> str:
        return _password_rules(v)


class UserPatch(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    role: Role | None = None
    is_active: bool | None = None
    scope_all: bool | None = None
    field_ids: list[str] | None = None
    operator_code: str | None = Field(default=None, max_length=32)


TokenOut.model_rebuild()
