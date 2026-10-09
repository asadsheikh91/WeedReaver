"""Flights, their images, the segmentation surface they produce, and processing jobs."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, BigInteger, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, Timestamped, UTCDateTime


class Survey(Timestamped, Base):
    __tablename__ = "surveys"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # S-01
    field_season_id: Mapped[str] = mapped_column(ForeignKey("field_seasons.id", ondelete="CASCADE"), index=True)
    role: Mapped[str] = mapped_column(String(16))
    status: Mapped[str] = mapped_column(String(16), index=True)
    flown_at: Mapped[datetime] = mapped_column(UTCDateTime)
    altitude_m: Mapped[int] = mapped_column(Integer, default=15)
    sensor: Mapped[str] = mapped_column(String(80), default="DJI Mavic 3M · 20 MP RGB")
    gsd_cm: Mapped[float] = mapped_column(Float, default=0.0)
    images: Mapped[int] = mapped_column(Integer, default=0)
    image_bytes: Mapped[int] = mapped_column(BigInteger, default=0)
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    stage: Mapped[str | None] = mapped_column(String(32))
    source: Mapped[str | None] = mapped_column(String(255))  # what was uploaded, for provenance
    error: Mapped[str | None] = mapped_column(Text)
    orthomosaic_key: Mapped[str | None] = mapped_column(String(255))
    pipeline: Mapped[str | None] = mapped_column(String(120))  # e.g. "OpenDroneMap · NodeODM"
    model_version: Mapped[str | None] = mapped_column(String(80))
    processed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))

    season = relationship("FieldSeason", lazy="joined")


class SurveyImage(Base):
    __tablename__ = "survey_images"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    survey_id: Mapped[str] = mapped_column(ForeignKey("surveys.id", ondelete="CASCADE"), index=True)
    filename: Mapped[str] = mapped_column(String(255))
    storage_key: Mapped[str] = mapped_column(String(255))
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    content_type: Mapped[str | None] = mapped_column(String(80))
    uploaded_at: Mapped[datetime] = mapped_column(UTCDateTime)


class SurveySurface(Timestamped, Base):
    """The segmentation output of a survey: either a synthetic patch spec or a raster file."""

    __tablename__ = "survey_surfaces"

    survey_id: Mapped[str] = mapped_column(ForeignKey("surveys.id", ondelete="CASCADE"), primary_key=True)
    kind: Mapped[str] = mapped_column(String(16))  # "patches" | "raster"
    spec: Mapped[dict | None] = mapped_column(JSON)  # PatchSurface spec
    storage_key: Mapped[str | None] = mapped_column(String(255))  # RasterSurface .npz
    model_version: Mapped[str | None] = mapped_column(String(80))


class ProcessingJob(Timestamped, Base):
    """A flight's trip through the pipeline: upload -> photogrammetry -> segmentation -> zoning."""

    __tablename__ = "processing_jobs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    survey_id: Mapped[str] = mapped_column(ForeignKey("surveys.id", ondelete="CASCADE"), index=True)
    status: Mapped[str] = mapped_column(String(16), index=True)
    stage: Mapped[str | None] = mapped_column(String(32))
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    error: Mapped[str | None] = mapped_column(Text)
    locked_by: Mapped[str | None] = mapped_column(String(80))
    locked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    started_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    result: Mapped[dict | None] = mapped_column(JSON)
