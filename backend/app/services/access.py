"""Who may see and do what.

Two rules govern the system:

* Role. Analysts and administrators decide (the dashboard surface). Operators and trainees
  observe (the phone surface). Definitions can only be changed by a deciding role.
* Scope. A user either sees every field or only the fields assigned to them (a trainee on one
  plot, say). Anything attached to a field inherits the field's visibility.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.errors import Forbidden, NotFound, OwnershipViolation
from app.domain.enums import DECIDING_ROLES, Role
from app.models import Field, User


def is_decider(user: User) -> bool:
    return Role(user.role) in DECIDING_ROLES


def require_decider(user: User, what: str = "This change") -> None:
    if not is_decider(user):
        raise OwnershipViolation(f"{what} is a definition owned by the dashboard; it needs an analyst or administrator")


def require_admin(user: User) -> None:
    if Role(user.role) != Role.ADMIN:
        raise Forbidden("Administrator only")


def field_scope(user: User) -> set[str] | None:
    """Ids the user may see, or None for every field."""
    if user.scope_all:
        return None
    return {f.id for f in user.fields}


def can_see_field(user: User, field_id: str) -> bool:
    scope = field_scope(user)
    return scope is None or field_id in scope


def get_field(db: Session, user: User, field_id: str, *, include_archived: bool = False) -> Field:
    f = db.get(Field, field_id)
    # Out-of-scope fields are reported as missing, not forbidden, so ids cannot be probed.
    if f is None or not can_see_field(user, field_id) or (f.archived_at is not None and not include_archived):
        raise NotFound(f"Field {field_id} not found")
    return f


def visible_fields_query(user: User, include_archived: bool = False):
    q = select(Field)
    if not include_archived:
        q = q.where(Field.archived_at.is_(None))
    scope = field_scope(user)
    if scope is not None:
        q = q.where(Field.id.in_(scope or {"__none__"}))
    return q.order_by(Field.created_at, Field.id)
