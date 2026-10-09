from __future__ import annotations

from datetime import datetime

from pydantic import Field as F
from pydantic import field_validator

from app.domain.enums import DOSE_UNITS, HracGroup, ScanStatus, WeedClass
from app.schemas.common import ApiModel


# ----------------------------------------------------------------------------- leaf scans


class ScanCreate(ApiModel):
    """A leaf scan, classified on the phone. The server records it; it never re-decides."""

    client_id: str = F(min_length=8, max_length=64)
    captured_at: datetime | None = None
    field_id: str
    zone_label: str | None = F(default=None, max_length=16)
    frame_id: str | None = F(default=None, max_length=32)
    species_latin: str = F(min_length=1, max_length=80)
    species_local: str | None = F(default=None, max_length=80)
    weed_class: WeedClass | None = None
    confidence: float = F(ge=0, le=1)
    abstained: bool
    runner_up: list[tuple[str, float]] = F(default_factory=list, max_length=10)
    inference_ms: int | None = F(default=None, ge=0, le=600_000)
    model_version: str | None = F(default=None, max_length=80)
    leaf_seed: int | None = None
    lat: float | None = F(default=None, ge=-90, le=90)
    lon: float | None = F(default=None, ge=-180, le=180)
    gnss_accuracy_m: float | None = F(default=None, ge=0, le=10_000)


class ScanOut(ApiModel):
    id: str
    client_id: str | None
    at: datetime
    field_id: str
    field_name: str
    zone_label: str | None
    frame_id: str | None
    species_latin: str
    species_local: str | None
    weed_class: WeedClass
    confidence: float
    abstained: bool
    runner_up: list[tuple[str, float]]
    inference_ms: int | None
    model_version: str | None
    leaf_seed: int | None
    lat: float | None
    lon: float | None
    gnss_accuracy_m: float | None
    device_id: str | None
    device_name: str | None
    operator_id: str | None
    has_photo: bool
    status: ScanStatus
    synced: bool = True
    annotation: str | None
    annotation_note: str | None
    annotated_by: str | None
    annotated_at: datetime | None
    resolved: bool
    resolution: str | None
    resolution_note: str | None
    resolved_by: str | None
    resolved_at: datetime | None
    #: The label to show: the analyst's, else the operator's, else the model's.
    display_label: str


class AnnotateIn(ApiModel):
    species: str = F(min_length=1, max_length=80)
    note: str | None = F(default=None, max_length=1000)


class ResolveIn(ApiModel):
    species: str = F(min_length=1, max_length=80)
    note: str | None = F(default=None, max_length=1000)


# ----------------------------------------------------------------------------- treatments


class TreatmentCreate(ApiModel):
    """Spec section 41.1: a dose is recorded exactly as written. The system publishes no rates."""

    client_id: str = F(min_length=8, max_length=64)
    field_id: str
    zone_labels: list[str] = F(min_length=1, max_length=52)
    applied_at: datetime | None = None
    product_id: str | None = None
    product: str | None = F(default=None, max_length=80)  # trade name, if not by id
    dose_recorded: str = F(min_length=1, max_length=32)
    dose_unit: str
    application_mode: str = F(min_length=1, max_length=40)
    growth_stage: str = F(min_length=1, max_length=40)
    area_acres: float = F(ge=0, le=10_000)
    water_litres: str = F(default="", max_length=16)
    notes: str = F(default="", max_length=2000)
    rotation_override: bool = False

    @field_validator("dose_unit")
    @classmethod
    def _unit(cls, v: str) -> str:
        if v not in DOSE_UNITS:
            raise ValueError(f"doseUnit must be one of: {', '.join(DOSE_UNITS)}")
        return v

    @field_validator("dose_recorded", "water_litres")
    @classmethod
    def _numberish(cls, v: str) -> str:
        v = v.strip()
        if v:
            try:
                if float(v) < 0:
                    raise ValueError
            except ValueError as exc:
                raise ValueError("must be a non-negative number as written on the record") from exc
        return v


class TreatmentOut(ApiModel):
    id: str
    client_id: str | None
    field_season_id: str
    field_id: str
    field_name: str
    zone_labels: list[str]
    applied_at: datetime
    product: str
    active_ingredient: str
    hrac_group: HracGroup
    hrac_display: str
    dose_recorded: str
    dose_unit: str
    application_mode: str
    growth_stage: str
    operator: str
    operator_id: str | None
    device_id: str | None
    area_acres: float
    synced: bool = True
    water_litres: str
    notes: str
    rotation_override: bool
    season: str
    created_at: datetime


class TreatmentResult(ApiModel):
    treatment: TreatmentOut
    rotation_warning: str | None


# ----------------------------------------------------------------------------- quadrats


class QuadratCreate(ApiModel):
    client_id: str | None = F(default=None, max_length=64)
    frame_id: str = F(min_length=1, max_length=32)
    recorded_at: datetime | None = None
    field_id: str
    zone_label: str | None = F(default=None, max_length=16)
    species_counts: list[tuple[str, int]] = F(min_length=1, max_length=50)
    verified_by: str | None = F(default=None, max_length=120)
    seed: int | None = None

    @field_validator("species_counts")
    @classmethod
    def _counts(cls, v: list[tuple[str, int]]) -> list[tuple[str, int]]:
        if any(c < 0 for _, c in v):
            raise ValueError("counts must be non-negative")
        return v


class QuadratOut(ApiModel):
    id: str
    frame_id: str
    recorded_at: datetime
    field_id: str
    field_name: str
    zone_label: str | None
    species_counts: list[tuple[str, int]]
    total: int
    verified_by: str | None
    seed: int | None
