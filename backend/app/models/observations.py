"""What the phone records: leaf scans, treatments, verifications and quadrat counts.

Records that a phone may create offline carry a `client_id` the phone generates; re-sending a
record (a retried sync) is then idempotent.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, Boolean, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, Timestamped, UTCDateTime


class LeafScan(Timestamped, Base):
    __tablename__ = "leaf_scans"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # SC-041
    client_id: Mapped[str | None] = mapped_column(String(64), unique=True)
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"), index=True)
    operator_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    captured_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    zone_label: Mapped[str | None] = mapped_column(String(16))
    frame_id: Mapped[str | None] = mapped_column(String(32))

    # ---- on-device inference ------------------------------------------------------------------
    species_latin: Mapped[str] = mapped_column(String(80))
    species_local: Mapped[str | None] = mapped_column(String(80))
    weed_class: Mapped[str] = mapped_column(String(16))
    confidence: Mapped[float] = mapped_column(Float)
    abstained: Mapped[bool] = mapped_column(Boolean, index=True)
    runner_up: Mapped[list] = mapped_column(JSON, default=list)  # [[latin, p], ...]
    inference_ms: Mapped[int | None] = mapped_column(Integer)
    model_version: Mapped[str | None] = mapped_column(String(80))
    leaf_seed: Mapped[int | None] = mapped_column(Integer)

    # ---- position -----------------------------------------------------------------------------
    lat: Mapped[float | None] = mapped_column(Float)
    lon: Mapped[float | None] = mapped_column(Float)
    gnss_accuracy_m: Mapped[float | None] = mapped_column(Float)

    # ---- photo ----------------------------------------------------------------------------------
    photo_key: Mapped[str | None] = mapped_column(String(255))
    photo_content_type: Mapped[str | None] = mapped_column(String(40))
    photo_bytes: Mapped[int | None] = mapped_column(Integer)

    # ---- review loop ----------------------------------------------------------------------------
    annotation: Mapped[str | None] = mapped_column(String(80))  # operator's suggestion in the field
    annotation_note: Mapped[str | None] = mapped_column(Text)
    annotated_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    annotated_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    resolved: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    resolution: Mapped[str | None] = mapped_column(String(80))  # the analyst's label
    resolution_note: Mapped[str | None] = mapped_column(Text)
    resolved_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    resolved_at: Mapped[datetime | None] = mapped_column(UTCDateTime)


class Treatment(Timestamped, Base):
    """Spec section 41.1. A dose is recorded exactly as written, never computed or prescribed."""

    __tablename__ = "treatments"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # T-0118, T-2025-A
    client_id: Mapped[str | None] = mapped_column(String(64), unique=True)
    field_season_id: Mapped[str] = mapped_column(ForeignKey("field_seasons.id", ondelete="CASCADE"), index=True)
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    zone_labels: Mapped[list] = mapped_column(JSON, default=list)
    applied_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    product: Mapped[str] = mapped_column(String(80))
    active_ingredient: Mapped[str] = mapped_column(String(120))
    hrac_group: Mapped[str] = mapped_column(String(8), index=True)
    dose_recorded: Mapped[str] = mapped_column(String(32))
    dose_unit: Mapped[str] = mapped_column(String(16))
    application_mode: Mapped[str] = mapped_column(String(40))
    growth_stage: Mapped[str] = mapped_column(String(40))
    operator_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    operator_name: Mapped[str] = mapped_column(String(120))
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"))
    area_acres: Mapped[float] = mapped_column(Float)
    water_litres: Mapped[str] = mapped_column(String(16), default="")
    notes: Mapped[str] = mapped_column(Text, default="")
    #: Recorded despite a same-group rotation warning (the phone's "Record anyway").
    rotation_override: Mapped[bool] = mapped_column(Boolean, default=False)


class Verification(Timestamped, Base):
    """Per-zone control measured between a pre-treatment and a follow-up survey."""

    __tablename__ = "verifications"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # V-001
    client_id: Mapped[str | None] = mapped_column(String(64), unique=True)
    field_season_id: Mapped[str] = mapped_column(ForeignKey("field_seasons.id", ondelete="CASCADE"), index=True)
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    role: Mapped[str] = mapped_column(String(16))  # PLUS_14D | PLUS_28D
    pre_survey_id: Mapped[str | None] = mapped_column(ForeignKey("surveys.id", ondelete="SET NULL"))
    follow_survey_id: Mapped[str | None] = mapped_column(ForeignKey("surveys.id", ondelete="SET NULL"))
    saved_at: Mapped[datetime] = mapped_column(UTCDateTime)
    saved_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"))
    results: Mapped[list] = mapped_column(JSON, default=list)  # [{letter, beforePct, afterPct, efficacyPct}]
    worst_efficacy_pct: Mapped[int | None] = mapped_column(Integer)
    field_delta_pct: Mapped[int | None] = mapped_column(Integer)
    chemical_saved_pct: Mapped[float | None] = mapped_column(Float)
    #: Imported from an earlier season's records rather than computed from stored surveys.
    historical: Mapped[bool] = mapped_column(Boolean, default=False)


class Quadrat(Timestamped, Base):
    """A ground-truth count inside a fiducial (ArUco) frame, verified by an agronomist."""

    __tablename__ = "quadrats"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # Q-01
    client_id: Mapped[str | None] = mapped_column(String(64), unique=True)
    frame_id: Mapped[str] = mapped_column(String(32))
    recorded_at: Mapped[datetime] = mapped_column(UTCDateTime)
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    zone_label: Mapped[str | None] = mapped_column(String(16))
    species_counts: Mapped[list] = mapped_column(JSON, default=list)  # [[latin, count], ...]
    verified_by: Mapped[str | None] = mapped_column(String(120))
    recorded_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    seed: Mapped[int | None] = mapped_column(Integer)
