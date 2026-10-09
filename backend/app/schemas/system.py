from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import Field as F

from app.domain.enums import ExportFormat, HracGroup, WeedClass
from app.schemas.common import ApiModel

# ----------------------------------------------------------------------------- catalog


class ProductOut(ApiModel):
    id: str
    trade: str
    active: str
    hrac: HracGroup
    hrac_display: str
    target: WeedClass
    crop: str
    formulation: str
    registered: bool
    updated_at: datetime


class ProductIn(ApiModel):
    trade: str = F(min_length=1, max_length=80)
    active: str = F(min_length=1, max_length=120)
    hrac: HracGroup
    target: WeedClass
    crop: str = F(min_length=1, max_length=40)
    formulation: str = F(min_length=1, max_length=80)
    registered: bool = True


class ProductPatch(ApiModel):
    active: str | None = F(default=None, max_length=120)
    hrac: HracGroup | None = None
    target: WeedClass | None = None
    crop: str | None = F(default=None, max_length=40)
    formulation: str | None = F(default=None, max_length=80)
    registered: bool | None = None


class SpeciesOut(ApiModel):
    latin: str
    local: str
    common: str
    cls: WeedClass
    note: str


# ----------------------------------------------------------------------------- settings


class StationOut(ApiModel):
    org_name: str
    season_label: str
    threshold_pct: float
    threshold_published_at: datetime | None
    threshold_published_by: str | None
    threshold_min_pct: int
    threshold_max_pct: int
    default_grid_m: int
    default_units: Literal["local", "metric"]
    leaf_abstain_below: float
    model_aerial: str
    model_leaf: str
    leaf_model_size_mb: float | None
    app_latest_version: str | None
    app_min_version: str | None
    season_length_days: int
    timezone: str
    server_time: datetime
    updated_at: datetime


class StationPatch(ApiModel):
    org_name: str | None = F(default=None, min_length=1, max_length=120)
    season_label: str | None = F(default=None, min_length=1, max_length=40)
    default_grid_m: Literal[1, 2, 5] | None = None
    default_units: Literal["local", "metric"] | None = None
    leaf_abstain_below: float | None = F(default=None, ge=0.05, le=0.99)
    model_aerial: str | None = F(default=None, max_length=80)
    model_leaf: str | None = F(default=None, max_length=80)
    leaf_model_size_mb: float | None = F(default=None, ge=0)
    app_latest_version: str | None = F(default=None, max_length=40)
    app_min_version: str | None = F(default=None, max_length=40)
    season_length_days: int | None = F(default=None, ge=60, le=365)


class ThresholdIn(ApiModel):
    threshold_pct: float


class ThresholdOut(ApiModel):
    threshold_pct: float
    previous_pct: float
    fields_rezoned: int
    zones: int
    published_at: datetime


class ConditionsOut(ApiModel):
    """Spray-window conditions. Spraying is a weather decision; the system shows, never decides."""

    temp_c: float
    wind_kmh: float
    gust_kmh: float
    wind_from: str
    humidity: float
    delta_t: float
    rain_free_hours: int
    updated_at: datetime
    source: str
    spray_window: Literal["good", "marginal", "poor"]
    notes: list[str]


# ----------------------------------------------------------------------------- devices & sync


class DeviceOut(ApiModel):
    id: str
    name: str
    model: str | None
    os: str | None
    app_version: str | None
    operator: str | None
    operator_id: str | None
    operator_code: str | None
    battery: int | None
    storage_mb: int | None
    pending_changes: int
    last_seen_at: datetime | None
    last_sync_at: datetime | None
    online: bool
    revoked: bool
    update_available: bool


class DeviceRegister(ApiModel):
    install_id: str = F(min_length=8, max_length=64)
    name: str = F(min_length=1, max_length=120)
    model: str | None = F(default=None, max_length=120)
    os: str | None = F(default=None, max_length=60)
    app_version: str | None = F(default=None, max_length=40)


class Heartbeat(ApiModel):
    battery: int | None = F(default=None, ge=0, le=100)
    storage_mb: int | None = F(default=None, ge=0)
    pending_changes: int | None = F(default=None, ge=0)
    app_version: str | None = F(default=None, max_length=40)


class ChangeOut(ApiModel):
    id: str
    seq: int
    entity: str
    op: str
    entity_id: str | None
    summary: str
    at: datetime
    received_at: datetime
    owned_by_mobile: bool
    owner: Literal["phone", "dashboard"]
    bytes: int
    device_id: str | None
    device_name: str | None
    status: Literal["applied", "duplicate", "rejected"]
    applied: bool
    reason: str | None


SyncEntity = Literal[
    "leaf_scan", "treatment", "treatment_zone", "verification", "quadrat", "abstention", "field",
    "field_season", "survey", "settings", "product",
]


class SyncChange(ApiModel):
    client_seq: int = F(ge=0)
    entity: SyncEntity
    op: str = F(min_length=1, max_length=32)
    at: datetime | None = None
    payload: dict[str, Any] = F(default_factory=dict)


class SyncPush(ApiModel):
    changes: list[SyncChange] = F(max_length=500)
    pending_after: int | None = F(default=None, ge=0)


class SyncResult(ApiModel):
    client_seq: int
    status: Literal["applied", "duplicate", "rejected"]
    entity: str
    entity_id: str | None = None
    change_id: str | None = None
    reason: str | None = None


class SyncPushOut(ApiModel):
    results: list[SyncResult]
    applied: int
    duplicates: int
    rejected: int
    server_time: datetime


class SyncPullOut(ApiModel):
    cursor: datetime
    full: bool
    settings: StationOut
    fields: list[dict]
    seasons: list[dict]
    surveys: list[dict]
    zones: list[dict]
    products: list[dict]
    species: list[dict]
    scans: list[dict]
    treatments: list[dict]
    verifications: list[dict]
    quadrats: list[dict]
    removed_fields: list[str]


# ----------------------------------------------------------------------------- exports


class ExportInclude(ApiModel):
    boundary: bool = True
    zones: bool = True
    cells: bool = True
    abstained: bool = False


class ExportCreate(ApiModel):
    field_id: str
    format: ExportFormat
    grid_size: Literal[1, 2, 5] | None = None
    threshold_pct: float | None = None  # default: the published threshold
    include: ExportInclude = F(default_factory=ExportInclude)


class ExportOut(ApiModel):
    id: str
    field_id: str
    field_name: str
    format: ExportFormat
    grid_size: int
    threshold_pct: float
    include: dict
    size_bytes: int
    size_kb: int
    cells: int
    zones: int
    filename: str
    at: datetime
    created_by: str | None
    download_url: str


class ExportPreview(ApiModel):
    filename: str
    format: ExportFormat
    size_bytes: int
    cells: int
    zones: int
    lines: int
    preview: str


# ----------------------------------------------------------------------------- journal


class AuditOut(ApiModel):
    id: str
    at: datetime
    who: str
    user_id: str | None
    action: str
    detail: str
    entity: str | None
    entity_id: str | None
