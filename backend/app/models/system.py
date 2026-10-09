"""Station settings, id counters, the sync ledger, the audit log and export jobs."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, Boolean, Float, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, Timestamped, UTCDateTime


class StationSettings(Timestamped, Base):
    """Single row (id=1). The definitions both surfaces read."""

    __tablename__ = "station_settings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    org_name: Mapped[str] = mapped_column(String(120))
    season_label: Mapped[str] = mapped_column(String(40))  # "Rabi 2026-27"
    #: The published prescription threshold. Set on the dashboard; phones read it only.
    threshold_pct: Mapped[float] = mapped_column(Float, default=10.0)
    threshold_published_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    threshold_published_by: Mapped[str | None] = mapped_column(String(120))
    default_grid_m: Mapped[int] = mapped_column(Integer, default=2)
    default_units: Mapped[str] = mapped_column(String(8), default="local")  # local | metric
    leaf_abstain_below: Mapped[float] = mapped_column(Float, default=0.65)
    model_aerial: Mapped[str] = mapped_column(String(80))
    model_leaf: Mapped[str] = mapped_column(String(80))
    leaf_model_size_mb: Mapped[float | None] = mapped_column(Float)
    app_latest_version: Mapped[str | None] = mapped_column(String(40))
    app_min_version: Mapped[str | None] = mapped_column(String(40))
    season_length_days: Mapped[int] = mapped_column(Integer, default=154)


class Counter(Base):
    """Monotonic counters behind the human-readable ids (F-047, SC-041, CL-12 ...)."""

    __tablename__ = "counters"

    name: Mapped[str] = mapped_column(String(32), primary_key=True)
    value: Mapped[int] = mapped_column(Integer, default=0)


class ChangeLogEntry(Base):
    """The sync ledger (spec section 43.3). One row per change accepted from or published to a
    phone, with the ownership rule that decided it."""

    __tablename__ = "change_log"
    __table_args__ = (UniqueConstraint("device_id", "client_seq", name="uq_change_log_device_seq"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # CL-12
    seq: Mapped[int] = mapped_column(Integer, index=True)
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"), index=True)
    client_seq: Mapped[int | None] = mapped_column(Integer)
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    entity: Mapped[str] = mapped_column(String(32), index=True)
    op: Mapped[str] = mapped_column(String(32))
    entity_id: Mapped[str | None] = mapped_column(String(64))
    summary: Mapped[str] = mapped_column(String(255))
    at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)  # when it happened (client clock)
    received_at: Mapped[datetime] = mapped_column(UTCDateTime)
    owned_by_mobile: Mapped[bool] = mapped_column(Boolean)
    bytes: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(16), default="applied")  # applied | duplicate | rejected
    reason: Mapped[str | None] = mapped_column(Text)


class AuditEntry(Base):
    """Every change to a definition, with who made it."""

    __tablename__ = "audit_log"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # A-10
    at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    who: Mapped[str] = mapped_column(String(120))
    action: Mapped[str] = mapped_column(String(80))
    detail: Mapped[str] = mapped_column(Text)
    entity: Mapped[str | None] = mapped_column(String(32))
    entity_id: Mapped[str | None] = mapped_column(String(64))


class ExportJob(Base):
    __tablename__ = "exports"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # X-04
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    format: Mapped[str] = mapped_column(String(16))
    grid_size: Mapped[int] = mapped_column(Integer)
    threshold_pct: Mapped[float] = mapped_column(Float)
    include: Mapped[dict] = mapped_column(JSON, default=dict)
    size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    cells: Mapped[int] = mapped_column(Integer, default=0)
    zones: Mapped[int] = mapped_column(Integer, default=0)
    filename: Mapped[str] = mapped_column(String(255))
    storage_key: Mapped[str | None] = mapped_column(String(255))
    created_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    note: Mapped[str | None] = mapped_column(Text)
