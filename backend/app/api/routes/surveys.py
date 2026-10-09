from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, File, Query, UploadFile, status
from fastapi.responses import FileResponse

from app.api.deps import CurrentUser, DbDep
from app.domain.enums import SurveyRole
from app.schemas.common import ApiModel, Message
from app.schemas.surveys import JobOut, ProcessIn, SurveyCreate, SurveyOut, SurveyPatch, UploadResult
from app.services import surveys

router = APIRouter(prefix="/surveys", tags=["flights"])


class UploadOpen(ApiModel):
    field_id: str
    role: SurveyRole
    flown_at: datetime | None = None


@router.get("", response_model=list[SurveyOut], summary="Flights across fields, oldest first")
def list_surveys(
    user: CurrentUser, db: DbDep,
    field_id: Annotated[str | None, Query(alias="fieldId")] = None,
    status_: Annotated[Literal["SCHEDULED", "QUEUED", "PROCESSING", "READY", "FAILED"] | None, Query(alias="status")] = None,
    role: Literal["PRE", "PLUS_14D", "PLUS_28D"] | None = None,
) -> list[SurveyOut]:
    return [surveys.survey_out(db, s) for s in surveys.list_surveys(db, user, field_id=field_id, status=status_, role=role)]


@router.post("", response_model=SurveyOut, status_code=status.HTTP_201_CREATED, summary="Schedule a flight")
def schedule(body: SurveyCreate, user: CurrentUser, db: DbDep) -> SurveyOut:
    s = surveys.schedule_survey(db, user, body)
    db.commit()
    return surveys.survey_out(db, s)


@router.post("/uploads", response_model=SurveyOut, status_code=status.HTTP_201_CREATED,
             summary="Step 1 of an upload: open (or reuse) the flight for a field and role")
def open_upload(body: UploadOpen, user: CurrentUser, db: DbDep) -> SurveyOut:
    s = surveys.open_upload(db, user, body.field_id, body.role, body.flown_at)
    db.commit()
    return surveys.survey_out(db, s)


@router.get("/{survey_id}", response_model=SurveyOut)
def get_survey(survey_id: str, user: CurrentUser, db: DbDep) -> SurveyOut:
    return surveys.survey_out(db, surveys.get_survey(db, user, survey_id))


@router.patch("/{survey_id}", response_model=SurveyOut, summary="Reschedule or correct a scheduled flight")
def patch_survey(survey_id: str, body: SurveyPatch, user: CurrentUser, db: DbDep) -> SurveyOut:
    s = surveys.patch_survey(db, user, surveys.get_survey(db, user, survey_id), body)
    db.commit()
    return surveys.survey_out(db, s)


@router.delete("/{survey_id}", response_model=Message, summary="Cancel a scheduled or failed flight")
def cancel(survey_id: str, user: CurrentUser, db: DbDep) -> Message:
    surveys.cancel_survey(db, user, surveys.get_survey(db, user, survey_id))
    db.commit()
    return Message(message=f"Flight {survey_id} cancelled")


@router.post("/{survey_id}/images", response_model=UploadResult,
             summary="Step 2: add images (send in batches; a .zip of images is accepted)")
def add_images(survey_id: str, user: CurrentUser, db: DbDep, files: Annotated[list[UploadFile], File()]) -> UploadResult:
    out = surveys.add_images(db, user, surveys.get_survey(db, user, survey_id), files)
    db.commit()
    return out


@router.post("/{survey_id}/process", response_model=JobOut, status_code=status.HTTP_202_ACCEPTED,
             summary="Step 3: queue photogrammetry, segmentation and zoning. Poll the flight for progress")
def process(survey_id: str, user: CurrentUser, db: DbDep, body: ProcessIn | None = None) -> JobOut:
    job = surveys.start_processing(db, user, surveys.get_survey(db, user, survey_id), body or ProcessIn())
    db.commit()
    return JobOut.model_validate(job)


@router.post("/{survey_id}/retry", response_model=JobOut, status_code=status.HTTP_202_ACCEPTED)
def retry(survey_id: str, user: CurrentUser, db: DbDep) -> JobOut:
    job = surveys.retry(db, user, surveys.get_survey(db, user, survey_id))
    db.commit()
    return JobOut.model_validate(job)


@router.get("/{survey_id}/orthomosaic", summary="Download the stitched orthomosaic, when one was produced")
def orthomosaic(survey_id: str, user: CurrentUser, db: DbDep) -> FileResponse:
    s = surveys.get_survey(db, user, survey_id)
    path = surveys.orthomosaic_path(s)
    return FileResponse(path, filename=f"{s.id}_orthomosaic{path.suffix}")
