from __future__ import annotations

from datetime import date, datetime
from typing import Literal

from pydantic import Field as F
from pydantic import model_validator

from app.domain.enums import Severity, SurveyRole, SurveyStatus
from app.schemas.common import ApiModel, LatLon, PointIn


class Landscape(ApiModel):
    """What the client imagery renderer draws around a parcel. Local metres, like the boundary."""

    seed: int
    canal: list[PointIn] | None = None
    road: list[PointIn] | None = None
    watercourse: list[PointIn] | None = None
    farmstead: PointIn | None = None
    village: PointIn | None = None
    tubewell: PointIn | None = None
    mustard_bias: float = 0.12


class AreaOut(ApiModel):
    sqm: float
    acres: float
    hectares: float
    kanal: int
    marla: int
    perimeter_m: float
    width_m: float
    height_m: float


class SeasonOut(ApiModel):
    id: str
    field_id: str
    crop: str
    season: str
    sowing_date: date | None
    row_spacing_cm: int | None
    variety: str | None
    harvest_date: date | None
    days_since_sowing: int | None = None
    updated_at: datetime | None = None


class LoopStep(ApiModel):
    key: Literal["survey", "route", "treat", "record", "verify"]
    label: str
    done: bool
    current: bool


class SurveyBrief(ApiModel):
    id: str
    role: SurveyRole
    status: SurveyStatus
    flown_at: datetime
    progress: float


class FieldOut(ApiModel):
    id: str
    name: str
    village: str
    boundary: list[PointIn]
    lat: float
    lon: float
    gate: PointIn
    landscape: dict
    capture_method: str
    captured_by: str
    area: AreaOut
    archived: bool
    created_at: datetime
    updated_at: datetime


class FieldSummary(FieldOut):
    season: SeasonOut | None
    surveyed: bool
    pressure: Severity
    zones_total: int
    zones_done: int
    zones_open: int
    last_survey: SurveyBrief | None
    next_survey: SurveyBrief | None
    loop: list[LoopStep]
    verified_at: datetime | None


class FieldDetail(FieldSummary):
    surveys: list[SurveyBrief]
    counts: dict[str, int]
    up_next: str


class FieldCreate(ApiModel):
    """A new parcel. Give the boundary either in WGS84 (`boundaryLatLon`, from an import or a
    GNSS walk) or in local metres with the anchor (`boundary` + `lat` + `lon`, from drawing on
    the imagery)."""

    client_id: str | None = F(default=None, max_length=64)
    name: str = F(min_length=1, max_length=80)
    village: str | None = F(default=None, max_length=120)
    capture_method: Literal["Surveyed", "Walked", "Drawn", "Imported"] = "Imported"
    boundary_lat_lon: list[LatLon] | None = None
    boundary: list[PointIn] | None = None
    lat: float | None = F(default=None, ge=-90, le=90)
    lon: float | None = F(default=None, ge=-180, le=180)
    gate: PointIn | None = None
    landscape: Landscape | None = None
    #: Douglas-Peucker tolerance for a walked trace; the phone uses 2 m.
    simplify_tolerance_m: float | None = F(default=None, ge=0, le=20)
    crop: str = F(default="Wheat", max_length=40)
    variety: str | None = F(default=None, max_length=60)
    sowing_date: date | None = None
    row_spacing_cm: int | None = F(default=None, ge=5, le=100)

    @model_validator(mode="after")
    def _one_boundary(self) -> "FieldCreate":
        if (self.boundary_lat_lon is None) == (self.boundary is None):
            raise ValueError("Provide exactly one of boundaryLatLon or boundary")
        if self.boundary is not None and (self.lat is None or self.lon is None):
            raise ValueError("A local-metre boundary needs its anchor lat and lon")
        pts = self.boundary_lat_lon or self.boundary or []
        if not 3 <= len(pts) <= 5000:
            raise ValueError("A boundary needs between 3 and 5000 vertices")
        return self


class FieldPatch(ApiModel):
    name: str | None = F(default=None, min_length=1, max_length=80)
    village: str | None = F(default=None, max_length=120)
    gate: PointIn | None = None
    landscape: Landscape | None = None
    boundary_lat_lon: list[LatLon] | None = None
    boundary: list[PointIn] | None = None


class BoundaryPreview(ApiModel):
    boundary: list[PointIn]
    boundary_lat_lon: list[LatLon]
    lat: float
    lon: float
    area: AreaOut
    vertices: int
    source_format: Literal["KML", "GeoJSON", "CSV"]
    name: str | None = None


class SeasonCreate(ApiModel):
    season: str | None = F(default=None, max_length=40)
    crop: str = F(default="Wheat", max_length=40)
    sowing_date: date | None = None
    row_spacing_cm: int | None = F(default=None, ge=5, le=100)
    variety: str | None = F(default=None, max_length=60)


class SeasonPatch(ApiModel):
    crop: str | None = F(default=None, max_length=40)
    sowing_date: date | None = None
    row_spacing_cm: int | None = F(default=None, ge=5, le=100)
    variety: str | None = F(default=None, max_length=60)
    harvest_date: date | None = None
