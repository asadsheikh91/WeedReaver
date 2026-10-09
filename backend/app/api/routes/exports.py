from __future__ import annotations

from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Query, Response, status
from sqlalchemy import select

from app.api.deps import CurrentUser, DbDep
from app.core.config import get_settings
from app.domain.enums import ExportFormat
from app.models import Field, User
from app.schemas.system import ExportCreate, ExportOut, ExportPreview
from app.services import exports

router = APIRouter(prefix="/exports", tags=["exports"])


def _out(db, jobs) -> list[ExportOut]:
    fields = {f.id: f.name for f in db.scalars(select(Field)).all()}
    names = {u.id: u.name for u in db.scalars(select(User)).all()}
    prefix = get_settings().api_prefix
    return [exports.export_out(j, fields.get(j.field_id, j.field_id), prefix, names) for j in jobs]


@router.get("", response_model=list[ExportOut])
def list_exports(user: CurrentUser, db: DbDep, field_id: Annotated[str | None, Query(alias="fieldId")] = None) -> list[ExportOut]:
    return _out(db, exports.list_jobs(db, user, field_id))


@router.post("", response_model=ExportOut, status_code=status.HTTP_201_CREATED,
             summary="Generate a GeoJSON, Shapefile or ISO 11783-10 TASKDATA file")
def create_export(body: ExportCreate, user: CurrentUser, db: DbDep) -> ExportOut:
    job = exports.create(db, user, body)
    db.commit()
    return _out(db, [job])[0]


@router.post("/preview", response_model=ExportPreview, summary="The first lines of the file, and its size, without saving")
def preview(body: ExportCreate, user: CurrentUser, db: DbDep) -> ExportPreview:
    return exports.preview(db, user, body)


@router.get("/{export_id}", response_model=ExportOut)
def get_export(export_id: str, user: CurrentUser, db: DbDep) -> ExportOut:
    return _out(db, [exports.get_job(db, user, export_id)])[0]


@router.get("/{export_id}/download", summary="Download the file")
def download(export_id: str, user: CurrentUser, db: DbDep) -> Response:
    job = exports.get_job(db, user, export_id)
    body = exports.file_for(db, user, job)
    db.commit()
    return Response(
        body, media_type=exports.MEDIA_TYPES[ExportFormat(job.format)],
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(job.filename)}"},
    )
