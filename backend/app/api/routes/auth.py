from __future__ import annotations

from fastapi import APIRouter, Request

from app.api.deps import CurrentUser, DbDep, client_meta
from app.core.config import get_settings
from app.core.ratelimit import SlidingWindowLimiter
from app.schemas.auth import (
    AcceptInviteIn, ChangePasswordIn, LoginIn, MeOut, Preferences, PreferencesPatch, RefreshIn, ResetConfirmIn,
    ResetRequestIn, TokenOut,
)
from app.schemas.common import Message
from app.services import users

router = APIRouter(prefix="/auth", tags=["auth"])

_s = get_settings()
login_limiter = SlidingWindowLimiter(_s.login_rate_limit, _s.login_rate_window_seconds)
reset_limiter = SlidingWindowLimiter(5, 3600)


@router.post("/login", response_model=TokenOut, summary="Sign in (dashboard or field app)")
def login(body: LoginIn, request: Request, db: DbDep) -> TokenOut:
    ip, agent = client_meta(request)
    key = f"{ip}|{body.email.lower()}"
    login_limiter.hit(key)
    out = users.login(db, body.email, body.password, body.device, agent)
    db.commit()
    login_limiter.reset(key)
    return out


@router.post("/refresh", response_model=TokenOut, summary="Rotate the refresh token")
def refresh(body: RefreshIn, request: Request, db: DbDep) -> TokenOut:
    out = users.refresh(db, body.refresh_token, client_meta(request)[1])
    db.commit()
    return out


@router.post("/logout", response_model=Message)
def logout(body: RefreshIn, db: DbDep) -> Message:
    users.logout(db, body.refresh_token)
    db.commit()
    return Message(message="Signed out")


@router.get("/me", response_model=MeOut)
def me(user: CurrentUser) -> MeOut:
    return users.me_out(user)


@router.patch("/me/preferences", response_model=Preferences, summary="Field-app settings for this user")
def patch_preferences(body: PreferencesPatch, user: CurrentUser, db: DbDep) -> Preferences:
    prefs = users.patch_preferences(db, user, body)
    db.commit()
    return prefs


@router.post("/change-password", response_model=Message)
def change_password(body: ChangePasswordIn, user: CurrentUser, db: DbDep) -> Message:
    users.change_password(db, user, body.current_password, body.new_password)
    db.commit()
    return Message(message="Password changed. Other sessions have been signed out.")


@router.post("/accept-invite", response_model=TokenOut, summary="Create an account from an invitation")
def accept_invite(body: AcceptInviteIn, request: Request, db: DbDep) -> TokenOut:
    out = users.accept_invitation(db, body, client_meta(request)[1])
    db.commit()
    return out


@router.post("/password-reset/request", summary="Always answers the same, whether or not the account exists")
def reset_request(body: ResetRequestIn, request: Request, db: DbDep) -> dict:
    reset_limiter.hit(f"{client_meta(request)[0]}|{body.email.lower()}")
    token = users.request_reset(db, body.email)
    db.commit()
    out: dict = {"message": "If that address has an account, a reset link is on its way."}
    if token and get_settings().environment != "production" and get_settings().mail_backend == "console":
        out["devToken"] = token  # development convenience only; never in production
    return out


@router.post("/password-reset/confirm", response_model=Message)
def reset_confirm(body: ResetConfirmIn, db: DbDep) -> Message:
    users.confirm_reset(db, body.token, body.new_password)
    db.commit()
    return Message(message="Password reset. Sign in with the new password.")
