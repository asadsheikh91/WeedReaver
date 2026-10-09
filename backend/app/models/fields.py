"""Parcels and their seasons.

The specification stores geometry as PostGIS geometry(MultiPolygon, 4326). Here a boundary is
held in the field's local metric frame (x east, y south) plus its WGS84 anchor, which is the
representation both clients compute with; WGS84 is derived exactly on output. This keeps the
schema portable (PostgreSQL in production, SQLite in tests) with no loss of precision.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import JSON, Date, Float, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, Timestamped, UTCDateTime


class Field(Timestamped, Base):
    __tablename__ = "fields"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # F-047
    client_id: Mapped[str | None] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(80))
    village: Mapped[str] = mapped_column(String(120))
    boundary: Mapped[list] = mapped_column(JSON)  # [[x, y], ...] local metres, NW corner at origin
    lat: Mapped[float] = mapped_column(Float)  # anchor of the local frame origin
    lon: Mapped[float] = mapped_column(Float)
    gate: Mapped[list] = mapped_column(JSON)  # [x, y]: where the spray route starts
    landscape: Mapped[dict] = mapped_column(JSON, default=dict)  # what the imagery renderer draws around it
    capture_method: Mapped[str] = mapped_column(String(16), default="Surveyed")
    captured_by_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    captured_by_name: Mapped[str] = mapped_column(String(120))
    area_sqm: Mapped[float] = mapped_column(Float)
    perimeter_m: Mapped[float] = mapped_column(Float)
    archived_at: Mapped[datetime | None] = mapped_column(UTCDateTime, index=True)

    seasons = relationship("FieldSeason", back_populates="field", order_by="FieldSeason.sowing_date", lazy="selectin")


class FieldSeason(Timestamped, Base):
    __tablename__ = "field_seasons"
    __table_args__ = (UniqueConstraint("field_id", "season", name="uq_field_seasons_field_season"),)

    id: Mapped[str] = mapped_column(String(40), primary_key=True)  # FS-047
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    crop: Mapped[str] = mapped_column(String(40), default="Wheat")
    season: Mapped[str] = mapped_column(String(40))  # "Rabi 2026-27"
    sowing_date: Mapped[date | None] = mapped_column(Date)
    row_spacing_cm: Mapped[int | None] = mapped_column(Integer)
    variety: Mapped[str | None] = mapped_column(String(60))
    harvest_date: Mapped[date | None] = mapped_column(Date)

    field = relationship("Field", back_populates="seasons", lazy="joined")
