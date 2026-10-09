"""Treatment zones: the one object both surfaces share (spec section 43.1).

Ownership is split by column. Geometry, severity and route order are definitions computed by
the dashboard from a survey at the published threshold; `state`, `treated_at` and the
efficacy measured on the ground are observations reported by the phone.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, Boolean, Float, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, Timestamped, UTCDateTime


class TreatmentZone(Timestamped, Base):
    __tablename__ = "treatment_zones"
    __table_args__ = (UniqueConstraint("field_season_id", "letter", name="uq_treatment_zones_season_letter"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    field_season_id: Mapped[str] = mapped_column(ForeignKey("field_seasons.id", ondelete="CASCADE"), index=True)
    field_id: Mapped[str] = mapped_column(ForeignKey("fields.id", ondelete="CASCADE"), index=True)
    survey_id: Mapped[str | None] = mapped_column(ForeignKey("surveys.id", ondelete="SET NULL"))
    letter: Mapped[str] = mapped_column(String(4))

    # ---- definitions (dashboard) ------------------------------------------------------------
    severity: Mapped[str] = mapped_column(String(16))
    dominant_class: Mapped[str] = mapped_column(String(16))
    area_sqm: Mapped[int] = mapped_column(Integer)
    cell_count: Mapped[int] = mapped_column(Integer)
    cx: Mapped[float] = mapped_column(Float)
    cy: Mapped[float] = mapped_column(Float)
    radius_m: Mapped[float] = mapped_column(Float)
    mean_infest_pct: Mapped[float] = mapped_column(Float)
    route_order: Mapped[int] = mapped_column(Integer)
    distance_m: Mapped[int] = mapped_column(Integer)
    threshold_pct: Mapped[float] = mapped_column(Float)
    geometry: Mapped[list] = mapped_column(JSON)  # polygons: [[exterior, *holes], ...] local metres
    #: False once a re-zoning no longer finds this zone. Kept, because its history matters.
    active: Mapped[bool] = mapped_column(Boolean, default=True)

    # ---- observations (phone) ---------------------------------------------------------------
    state: Mapped[str] = mapped_column(String(16), default="FLAGGED")
    state_changed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    treated_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    treated_by: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    efficacy_pct: Mapped[int | None] = mapped_column(Integer)

    @property
    def label(self) -> str:
        return f"Zone {self.letter}"

    @property
    def code(self) -> str:
        return f"Z-{self.letter}"
