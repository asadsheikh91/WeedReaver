"""Accounts, sessions, invitations and password resets."""

from __future__ import annotations

import uuid
from datetime import timedelta

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core import clock
from app.core.config import get_settings
from app.core.errors import Conflict, Forbidden, Invalid, NotFound, Unauthorized
from app.core.security import (
    create_access_token, hash_password, needs_rehash, new_opaque_token, token_digest, verify_password,
)
from app.domain.enums import ROLE_LABEL, Role
from app.models import Field, Invitation, PasswordReset, RefreshToken, User
from app.schemas.auth import (
    AcceptInviteIn, DeviceInfo, InvitationOut, InviteIn, MeOut, Preferences, PreferencesPatch, TokenOut, UserOut, UserPatch,
)
from app.services import access, ids, journal, mailer
from app.services.devices import register_device

# How long a just-rotated refresh token still counts as a retry rather than a replay.
RETRY_GRACE = timedelta(seconds=30)


def scope_label(u: User) -> str:
    if u.scope_all:
        return "All fields"
    names = sorted(f.name for f in u.fields if f.archived_at is None)
    return ", ".join(names) if names else "No fields assigned"


def user_out(u: User) -> UserOut:
    return UserOut(
        id=u.id, email=u.email, name=u.name, role=Role(u.role), role_label=ROLE_LABEL[Role(u.role)],
        operator_code=u.operator_code, is_active=u.is_active, scope_all=u.scope_all,
        field_ids=sorted(f.id for f in u.fields), scope_label=scope_label(u), last_active_at=u.last_active_at,
        created_at=u.created_at,
    )


def preferences_of(u: User) -> Preferences:
    return Preferences.model_validate(u.preferences or {})


def me_out(u: User) -> MeOut:
    return MeOut(**user_out(u).model_dump(), preferences=preferences_of(u))


def patch_preferences(db: Session, u: User, data: PreferencesPatch) -> Preferences:
    merged = preferences_of(u).model_dump()
    merged.update(data.model_dump(exclude_unset=True))
    prefs = Preferences.model_validate(merged)
    u.preferences = prefs.model_dump()
    u.updated_at = clock.now()
    return prefs


def get_by_email(db: Session, email: str) -> User | None:
    return db.scalar(select(User).where(User.email == email.strip().lower()))


# ----------------------------------------------------------------------------- sessions


def issue_tokens(db: Session, user: User, *, device_id: str | None = None, user_agent: str | None = None,
                 family_id: str | None = None) -> tuple[str, str, int, RefreshToken]:
    access_token, ttl = create_access_token(user.id, user.role, device_id=device_id)
    raw = new_opaque_token()
    now = clock.now()
    rt = RefreshToken(
        id=str(uuid.uuid4()), user_id=user.id, token_hash=token_digest(raw), family_id=family_id or str(uuid.uuid4()),
        device_id=device_id, user_agent=(user_agent or "")[:255] or None, created_at=now,
        expires_at=now + timedelta(days=get_settings().refresh_token_days),
    )
    db.add(rt)
    return access_token, raw, ttl, rt


def token_response(user: User, access_token: str, refresh: str, ttl: int, device_id: str | None) -> TokenOut:
    return TokenOut(access_token=access_token, refresh_token=refresh, expires_in=ttl, user=user_out(user), device_id=device_id)


def login(db: Session, email: str, password: str, device: DeviceInfo | None, user_agent: str | None) -> TokenOut:
    user = get_by_email(db, email)
    if not verify_password(password, user.password_hash if user else None) or user is None:
        raise Unauthorized("Email or password is incorrect", code="invalid_credentials")
    if not user.is_active:
        raise Unauthorized("This account has been deactivated", code="account_disabled")
    if user.password_hash and needs_rehash(user.password_hash):
        user.password_hash = hash_password(password)
    device_id = register_device(db, user, device).id if device else None
    user.last_active_at = clock.now()
    a, r, ttl, _ = issue_tokens(db, user, device_id=device_id, user_agent=user_agent)
    return token_response(user, a, r, ttl, device_id)


def refresh(db: Session, raw: str, user_agent: str | None) -> TokenOut:
    rt = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_digest(raw)))
    now = clock.now()
    if rt is None:
        raise Unauthorized("Invalid refresh token", code="invalid_refresh_token")
    nxt = db.get(RefreshToken, rt.replaced_by) if rt.revoked_at is not None and rt.replaced_by else None
    if nxt is not None and nxt.revoked_at is None and now - rt.revoked_at <= RETRY_GRACE:
        # The answer to the last refresh was lost (the phone never saw the new token): rotate from it instead.
        rt = nxt
    elif rt.revoked_at is not None:
        # A rotated token came back: it was stolen or replayed. End the whole session family.
        db.execute(update(RefreshToken).where(RefreshToken.family_id == rt.family_id, RefreshToken.revoked_at.is_(None))
                   .values(revoked_at=now))
        db.commit()
        raise Unauthorized("Refresh token was already used; sign in again", code="refresh_token_reused")
    if rt.expires_at < now:
        raise Unauthorized("Session expired; sign in again", code="refresh_token_expired")
    user = db.get(User, rt.user_id)
    if user is None or not user.is_active:
        raise Unauthorized("This account has been deactivated", code="account_disabled")
    a, r, ttl, new = issue_tokens(db, user, device_id=rt.device_id, user_agent=user_agent, family_id=rt.family_id)
    rt.revoked_at = now
    rt.replaced_by = new.id
    user.last_active_at = now
    return token_response(user, a, r, ttl, rt.device_id)


def logout(db: Session, raw: str) -> None:
    rt = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_digest(raw)))
    if rt is not None and rt.revoked_at is None:
        rt.revoked_at = clock.now()


def revoke_all(db: Session, user_id: str) -> None:
    db.execute(update(RefreshToken).where(RefreshToken.user_id == user_id, RefreshToken.revoked_at.is_(None))
               .values(revoked_at=clock.now()))


def change_password(db: Session, user: User, current: str, new: str, *, device_id: str | None = None,
                    user_agent: str | None = None) -> TokenOut:
    """Signs out every session, then gives the caller a fresh one."""
    if not verify_password(current, user.password_hash):
        raise Invalid("Current password is incorrect", code="invalid_credentials")
    if current == new:
        raise Invalid("Choose a password different from the current one")
    user.password_hash = hash_password(new)
    user.password_changed_at = clock.now()
    revoke_all(db, user.id)
    journal.audit(db, user, "Password changed", user.email, entity="user", entity_id=user.id)
    a, r, ttl, _ = issue_tokens(db, user, device_id=device_id, user_agent=user_agent)
    return token_response(user, a, r, ttl, device_id)


# ----------------------------------------------------------------------------- invitations


def _invitation_status(inv: Invitation) -> str:
    if inv.revoked_at:
        return "revoked"
    if inv.accepted_at:
        return "accepted"
    if inv.expires_at < clock.now():
        return "expired"
    return "pending"


def invitation_out(inv: Invitation, token: str | None = None) -> InvitationOut:
    link = f"{get_settings().public_web_url.rstrip('/')}/accept-invite?token={token}" if token else None
    return InvitationOut(
        id=inv.id, email=inv.email, role=Role(inv.role), name=inv.name, field_ids=inv.field_ids, created_at=inv.created_at,
        expires_at=inv.expires_at, accepted_at=inv.accepted_at, revoked_at=inv.revoked_at,
        status=_invitation_status(inv), token=token, link=link,  # type: ignore[arg-type]
    )


def _check_assignable_role(actor: User, role: Role) -> None:
    access.require_decider(actor, "Managing the team")
    if Role(actor.role) == Role.ANALYST and role in (Role.ADMIN, Role.ANALYST):
        raise Forbidden("Only an administrator can add analysts or administrators")


def _valid_field_ids(db: Session, field_ids: list[str] | None) -> list[str] | None:
    if field_ids is None:
        return None
    out = []
    for fid in dict.fromkeys(field_ids):
        if db.get(Field, fid) is None:
            raise Invalid(f"Unknown field {fid}")
        out.append(fid)
    return out


def invite(db: Session, actor: User, data: InviteIn) -> InvitationOut:
    _check_assignable_role(actor, data.role)
    email = data.email.strip().lower()
    if get_by_email(db, email) is not None:
        raise Conflict(f"{email} already has an account")
    for inv in db.scalars(select(Invitation).where(Invitation.email == email, Invitation.accepted_at.is_(None),
                                                   Invitation.revoked_at.is_(None))):
        inv.revoked_at = clock.now()  # a fresh invitation replaces any pending one
    raw = new_opaque_token()
    now = clock.now()
    inv = Invitation(
        id=str(uuid.uuid4()), email=email, role=data.role.value, name=data.name, field_ids=_valid_field_ids(db, data.field_ids),
        token_hash=token_digest(raw), invited_by=actor.id, created_at=now,
        expires_at=now + timedelta(days=get_settings().invitation_days),
    )
    db.add(inv)
    journal.audit(db, actor, "Teammate invited", f"{email} · {ROLE_LABEL[data.role]}", entity="invitation", entity_id=inv.id)
    out = invitation_out(inv, raw)
    mailer.send(email, "You have been invited to WeedReaver",
                f"{actor.name} invited you to join as {ROLE_LABEL[data.role]}.\n\nAccept: {out.link}\n\n"
                f"The link expires in {get_settings().invitation_days} days.")
    return out


def list_invitations(db: Session, actor: User) -> list[InvitationOut]:
    access.require_decider(actor, "The team")
    return [invitation_out(i) for i in db.scalars(select(Invitation).order_by(Invitation.created_at.desc())).all()]


def revoke_invitation(db: Session, actor: User, invitation_id: str) -> None:
    access.require_decider(actor, "The team")
    inv = db.get(Invitation, invitation_id)
    if inv is None:
        raise NotFound("Invitation not found")
    if inv.accepted_at:
        raise Conflict("That invitation was already accepted")
    inv.revoked_at = clock.now()


def accept_invitation(db: Session, data: AcceptInviteIn, user_agent: str | None) -> TokenOut:
    inv = db.scalar(select(Invitation).where(Invitation.token_hash == token_digest(data.token)))
    if inv is None or _invitation_status(inv) != "pending":
        raise Invalid("This invitation link is invalid or has expired", code="invalid_invitation")
    if get_by_email(db, inv.email) is not None:
        raise Conflict(f"{inv.email} already has an account")
    role = Role(inv.role)
    user = User(
        id=ids.next_id(db, "user", User), email=inv.email, name=data.name.strip(), role=role.value,
        password_hash=hash_password(data.password), is_active=True, scope_all=inv.field_ids is None,
        preferences=Preferences().model_dump(), password_changed_at=clock.now(), last_active_at=clock.now(),
    )
    if inv.field_ids:
        user.fields = [f for f in (db.get(Field, fid) for fid in inv.field_ids) if f is not None]
    if role in (Role.OPERATOR, Role.TRAINEE):
        user.operator_code = ids.next_id(db, "operator")
    db.add(user)
    inv.accepted_at = clock.now()
    db.flush()
    journal.audit(db, user, "Invitation accepted", f"{user.email} · {ROLE_LABEL[role]}", entity="user", entity_id=user.id)
    device_id = register_device(db, user, data.device).id if data.device else None
    a, r, ttl, _ = issue_tokens(db, user, device_id=device_id, user_agent=user_agent)
    return token_response(user, a, r, ttl, device_id)


# ----------------------------------------------------------------------------- password reset


def request_reset(db: Session, email: str) -> str | None:
    """Always succeeds from the caller's point of view; returns the token for development."""
    user = get_by_email(db, email)
    if user is None or not user.is_active:
        return None
    raw = new_opaque_token()
    now = clock.now()
    db.add(PasswordReset(id=str(uuid.uuid4()), user_id=user.id, token_hash=token_digest(raw), created_at=now,
                         expires_at=now + timedelta(minutes=get_settings().password_reset_minutes)))
    link = f"{get_settings().public_web_url.rstrip('/')}/reset-password?token={raw}"
    mailer.send(user.email, "Reset your WeedReaver password", f"Reset your password: {link}\n\nIgnore this if it was not you.")
    return raw


def confirm_reset(db: Session, token: str, new_password: str) -> None:
    pr = db.scalar(select(PasswordReset).where(PasswordReset.token_hash == token_digest(token)))
    if pr is None or pr.used_at is not None or pr.expires_at < clock.now():
        raise Invalid("This reset link is invalid or has expired", code="invalid_reset_token")
    user = db.get(User, pr.user_id)
    if user is None or not user.is_active:
        raise Invalid("This reset link is invalid or has expired", code="invalid_reset_token")
    user.password_hash = hash_password(new_password)
    user.password_changed_at = clock.now()
    pr.used_at = clock.now()
    revoke_all(db, user.id)
    journal.audit(db, user, "Password reset", user.email, entity="user", entity_id=user.id)


# ----------------------------------------------------------------------------- team


def list_users(db: Session, actor: User) -> list[User]:
    access.require_decider(actor, "The team")
    return list(db.scalars(select(User).order_by(User.created_at, User.id)).all())


def _active_admins(db: Session) -> int:
    return len(db.scalars(select(User.id).where(User.role == Role.ADMIN.value, User.is_active.is_(True))).all())


def patch_user(db: Session, actor: User, user_id: str, data: UserPatch) -> User:
    access.require_decider(actor, "The team")
    target = db.get(User, user_id)
    if target is None:
        raise NotFound(f"User {user_id} not found")
    is_admin = Role(actor.role) == Role.ADMIN
    changes = data.model_dump(exclude_unset=True)
    if not is_admin:
        allowed = {"scope_all", "field_ids", "operator_code"}
        if set(changes) - allowed or Role(target.role) not in (Role.OPERATOR, Role.TRAINEE):
            raise Forbidden("Analysts can only change which fields operators and trainees see")
    if "role" in changes and changes["role"] is not None:
        new_role = Role(changes["role"])
        if target.id == actor.id and new_role != Role.ADMIN and _active_admins(db) <= 1:
            raise Conflict("You are the last administrator")
        target.role = new_role.value
    if "is_active" in changes and changes["is_active"] is not None:
        if not changes["is_active"] and target.role == Role.ADMIN and _active_admins(db) <= 1:
            raise Conflict("Cannot deactivate the last administrator")
        target.is_active = changes["is_active"]
        if not target.is_active:
            revoke_all(db, target.id)
    if changes.get("name"):
        target.name = changes["name"].strip()
    if "operator_code" in changes:
        code = (changes["operator_code"] or "").strip() or None
        if code and db.scalar(select(User).where(User.operator_code == code, User.id != target.id)):
            raise Conflict(f"Operator code {code} is taken")
        target.operator_code = code
    if "scope_all" in changes and changes["scope_all"] is not None:
        target.scope_all = changes["scope_all"]
    if "field_ids" in changes and changes["field_ids"] is not None:
        ids_ = _valid_field_ids(db, changes["field_ids"]) or []
        target.fields = [db.get(Field, fid) for fid in ids_]  # type: ignore[misc]
        if "scope_all" not in changes:
            target.scope_all = False
    target.updated_at = clock.now()
    journal.audit(db, actor, "Teammate updated", f"{target.name} · {', '.join(sorted(changes))}", entity="user", entity_id=target.id)
    return target
