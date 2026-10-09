from __future__ import annotations

from fastapi import APIRouter, status

from app.api.deps import CurrentUser, DbDep
from app.schemas.auth import InvitationOut, InviteIn, UserOut, UserPatch
from app.schemas.common import Message
from app.services import users

router = APIRouter(prefix="/users", tags=["team"])


@router.get("", response_model=list[UserOut], summary="Everyone who can sign in")
def list_users(user: CurrentUser, db: DbDep) -> list[UserOut]:
    return [users.user_out(u) for u in users.list_users(db, user)]


@router.patch("/{user_id}", response_model=UserOut)
def patch_user(user_id: str, body: UserPatch, user: CurrentUser, db: DbDep) -> UserOut:
    target = users.patch_user(db, user, user_id, body)
    db.commit()
    return users.user_out(target)


@router.post("/invitations", response_model=InvitationOut, status_code=status.HTTP_201_CREATED,
             summary="Invite a teammate (the link is also returned, for delivery without a mail server)")
def invite(body: InviteIn, user: CurrentUser, db: DbDep) -> InvitationOut:
    out = users.invite(db, user, body)
    db.commit()
    return out


@router.get("/invitations", response_model=list[InvitationOut])
def invitations(user: CurrentUser, db: DbDep) -> list[InvitationOut]:
    return users.list_invitations(db, user)


@router.delete("/invitations/{invitation_id}", response_model=Message)
def revoke_invitation(invitation_id: str, user: CurrentUser, db: DbDep) -> Message:
    users.revoke_invitation(db, user, invitation_id)
    db.commit()
    return Message(message="Invitation revoked")
