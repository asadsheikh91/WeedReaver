from __future__ import annotations

from datetime import datetime

from pydantic import Field as F

from app.domain.enums import JobStatus, SurveyRole, SurveyStatus
from app.schemas.common import ApiModel, LatLon


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
    #: Files the flight already held (same name and size): a retried or resumed upload.
    duplicates: int = 0
    rejected: list[dict]
    images: int
    image_bytes: int
    #: Images in the flight whose EXIF carries a GPS position.
    with_gps: int = 0


class FlightImageOut(ApiModel):
    filename: str
    size_bytes: int
    has_gps: bool


class FlightImagesOut(ApiModel):
    survey_id: str
    images: int
    image_bytes: int
    with_gps: int
    #: Mean camera position, when any image has one.
    gps_centre: LatLon | None
    #: Metres from that position to the field (0 inside it).
    offset_from_field_m: float | None
    files: list[FlightImageOut]


class OrthoBounds(ApiModel):
    west: float
    south: float
    east: float
    north: float


class OrthomosaicOut(ApiModel):
    """How a web map shows a flight's orthomosaic: XYZ tiles in Web Mercator."""

    survey_id: str
    field_id: str
    role: SurveyRole
    flown_at: datetime
    processed_at: datetime | None
    bounds: OrthoBounds
    #: Zoom at which the mosaic fits about one tile, and the zoom of its native resolution.
    min_zoom: int
    max_zoom: int
    gsd_cm: float
    width: int
    height: int
    epsg: int | None
    size_bytes: int
    #: Changes when the flight is processed again; part of every tile URL.
    version: str
    #: Relative to the API root, with {z}/{x}/{y} placeholders.
    tile_url: str
    download_url: str


class ProcessIn(ApiModel):
    """Finish an upload and queue processing. `simulateImages` lets a demonstration run the
    pipeline without real files (refused in production)."""

    simulate_images: int | None = F(default=None, ge=1, le=5000)
    source: str | None = F(default=None, max_length=255)
