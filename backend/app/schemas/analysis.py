from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import Field as F

from app.domain.enums import HracGroup, Severity, WeedClass, ZoneState
from app.schemas.common import ApiModel, PointIn


class ZoneOut(ApiModel):
    id: str
    code: str  # Z-A
    label: str  # Zone A
    letter: str
    field_id: str
    field_season_id: str
    survey_id: str | None
    severity: Severity
    dominant_class: WeedClass
    area_sqm: int
    cell_count: int
    cx: float
    cy: float
    radius_m: float
    distance_m: int
    route_order: int
    mean_infest_pct: float
    threshold_pct: float
    state: ZoneState
    state_changed_at: datetime | None
    treated_at: datetime | None
    efficacy_pct: int | None
    active: bool
    geometry: list | None = None  # [[exterior, *holes], ...] in local metres
    updated_at: datetime


class ZonePreview(ApiModel):
    """A zone computed at an arbitrary threshold; not published."""

    code: str
    label: str
    letter: str
    severity: Severity
    dominant_class: WeedClass
    area_sqm: int
    cell_count: int
    cx: float
    cy: float
    radius_m: float
    distance_m: int
    route_order: int
    mean_infest_pct: float
    geometry: list | None = None


class ZoneStateIn(ApiModel):
    state: Literal["FLAGGED", "ROUTED", "TREATED"]
    at: datetime | None = None  # when it happened on the phone


class GridStats(ApiModel):
    cell_meters: int
    threshold_pct: float
    total: int
    flagged: int
    abstained: int
    treated_fraction: float
    treated_sqm: float
    treated_acres: float


class GridCompare(ApiModel):
    field_id: str
    threshold_pct: float
    survey_id: str
    grids: list[GridStats]
    note: str


class CellOut(ApiModel):
    col: int
    row: int
    ref: str
    inside: bool
    infest_pct: float
    severity: Severity
    weed_class: WeedClass
    confidence: float
    abstained: bool
    treated: bool
    x: float
    y: float
    size_m: int


class RouteLeg(ApiModel):
    letter: str
    label: str
    zone_id: str
    order: int
    from_x: float
    from_y: float
    to_x: float
    to_y: float
    distance_m: float
    cumulative_m: float
    bearing_deg: float
    compass: str
    instruction: str
    state: ZoneState


class RouteOut(ApiModel):
    field_id: str
    start: PointIn
    from_gate: bool
    legs: list[RouteLeg]
    total_m: float
    remaining: int


class ZoneEfficacyOut(ApiModel):
    letter: str
    label: str
    before_pct: float
    after_pct: float
    efficacy_pct: int
    inspect: bool


class VerificationPreview(ApiModel):
    field_id: str
    role: Literal["PLUS_14D", "PLUS_28D"]
    ready: bool
    reason: str | None
    pre_survey_id: str | None
    follow_survey_id: str | None
    follow_status: str | None
    follow_progress: float | None
    follow_flown_at: datetime | None
    zones: list[ZoneEfficacyOut]
    worst: ZoneEfficacyOut | None
    weak_count: int
    field_delta_pct: int | None
    chemical_saved_fraction: float | None
    before_flagged: int | None
    after_flagged: int | None
    saved: "VerificationOut | None"
    caveat: str


class VerificationOut(ApiModel):
    id: str
    field_id: str
    field_season_id: str
    role: str
    pre_survey_id: str | None
    follow_survey_id: str | None
    saved_at: datetime
    saved_by: str | None
    results: list[dict]
    worst_efficacy_pct: int | None
    field_delta_pct: int | None
    chemical_saved_pct: float | None
    historical: bool


class VerificationSave(ApiModel):
    client_id: str | None = F(default=None, max_length=64)
    role: Literal["PLUS_14D", "PLUS_28D"] = "PLUS_14D"
    at: datetime | None = None


class RotationSeason(ApiModel):
    season: str
    hrac_groups: list[HracGroup]
    hrac_display: list[str]
    treatments: list[str]
    control_pct: int | None


class RotationProduct(ApiModel):
    id: str
    trade: str
    active: str
    hrac: HracGroup
    hrac_display: str
    target: WeedClass
    crop: str
    formulation: str


class RotationOut(ApiModel):
    field_id: str
    seasons: list[RotationSeason]
    trend: list[dict]  # [{season, hrac, controlPct}] for the trend chart
    latest_group: HracGroup | None
    latest_group_display: str | None
    streak: int
    risk: Literal["High", "Watch", "Low"]
    acceptable_control_pct: int
    warning: str | None
    used_groups: list[HracGroup]
    alternatives: list[RotationProduct]
    next_season: str


class RotationCheckIn(ApiModel):
    field_id: str
    product_id: str | None = None
    product: str | None = None  # trade name, accepted for convenience


class RotationCheckOut(ApiModel):
    field_id: str
    product: str
    hrac: HracGroup
    hrac_display: str
    clash: bool
    streak: int
    last_group: HracGroup | None
    message: str
    alternatives: list[RotationProduct]


VerificationPreview.model_rebuild()
