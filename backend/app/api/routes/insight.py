from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Query
from sqlalchemy import func, select

from app.api.deps import CurrentUser, DbDep, PagingDep
from app.models import AuditEntry
from app.schemas.common import Page
from app.schemas.system import AuditOut
from app.services import access, insight

router = APIRouter(tags=["overview"])

Kind = Literal["TREATMENT", "SCAN", "ZONE", "VERIFY", "SURVEY", "FIELD"]


@router.get("/overview", summary="What needs a decision today, with headline numbers")
def overview(user: CurrentUser, db: DbDep) -> dict:
    return insight.overview(db, user)


@router.get("/activity", summary="One timeline across fields and sources, newest first")
def activity(
    user: CurrentUser, db: DbDep,
    field_id: Annotated[str | None, Query(alias="fieldId")] = None,
    kind: Annotated[list[Kind] | None, Query()] = None,
    before: Annotated[datetime | None, Query(description="Cursor: return entries older than this")] = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
) -> dict:
    items = insight.activity(db, user, field_id=field_id, kinds=set(kind) if kind else None, before=before, limit=limit)
    next_cursor = items[-1]["at"].isoformat() if len(items) == limit else None
    return {"items": [{**e, "at": e["at"].isoformat()} for e in items], "nextBefore": next_cursor}


@router.get("/season", summary="Season calendar: sowing and every flight per field")
def season(user: CurrentUser, db: DbDep) -> dict:
    return insight.season_calendar(db, user)


@router.get("/audit", response_model=Page[AuditOut], summary="Every change to a definition, with who made it")
def audit(user: CurrentUser, db: DbDep, paging: PagingDep, entity: str | None = None) -> Page[AuditOut]:
    access.require_decider(user, "The audit log")
    q = select(AuditEntry)
    if entity:
        q = q.where(AuditEntry.entity == entity)
    total = int(db.scalar(select(func.count()).select_from(q.subquery())) or 0)
    rows = db.scalars(q.order_by(AuditEntry.at.desc(), AuditEntry.id.desc()).limit(paging.limit).offset(paging.offset)).all()
    return Page(items=[AuditOut.model_validate(a) for a in rows], total=total, limit=paging.limit, offset=paging.offset)
