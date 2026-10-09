from __future__ import annotations

from datetime import datetime

from pydantic import Field as F

from app.domain.enums import JobStatus, SurveyRole, SurveyStatus
from app.schemas.common import ApiModel


class JobOut(ApiModel):
    id: str
    status: JobStatus
    stage: str | None
    progress: float
    attempts: int
    error: str | None
    started_at: datetime | None
    finished_at: datetime | None


class SurveyOut(ApiModel):
    id: str
    field_season_id: str
    field_id: str
    field_name: str
    role: SurveyRole
    role_label: str
    status: SurveyStatus
    flown_at: datetime
    altitude_m: int
    sensor: str
    gsd_cm: float
    images: int
    image_bytes: int
    progress: float
    stage: str | None
    source: str | None
    error: str | None
    pipeline: str | None
    model_version: str | None
    processed_at: datetime | None
    has_orthomosaic: bool
    has_surface: bool
    job: JobOut | None = None
    created_at: datetime
    updated_at: datetime


class SurveyCreate(ApiModel):
    """Schedule a flight (or open one for upload straight away)."""

    field_id: str
    role: SurveyRole
    flown_at: datetime | None = None
    altitude_m: int = F(default=15, ge=5, le=120)
    sensor: str = F(default="DJI Mavic 3M · 20 MP RGB", max_length=80)


class SurveyPatch(ApiModel):
    flown_at: datetime | None = None
    altitude_m: int | None = F(default=None, ge=5, le=120)
    sensor: str | None = F(default=None, max_length=80)


class UploadResult(ApiModel):
    survey_id: str
    received: int
    rejected: list[dict]
    images: int
    image_bytes: int


class ProcessIn(ApiModel):
    """Finish an upload and queue processing. `simulateImages` lets a demonstration run the
    pipeline without real files (refused in production)."""

    simulate_images: int | None = F(default=None, ge=1, le=5000)
    source: str | None = F(default=None, max_length=255)
