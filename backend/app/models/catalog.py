"""Reference data the dashboard owns: herbicide label data and the leaf-scanner species set."""

from __future__ import annotations

from sqlalchemy import Boolean, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, Timestamped


class Product(Timestamped, Base):
    __tablename__ = "products"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # P-01
    trade: Mapped[str] = mapped_column(String(80), unique=True)
    active: Mapped[str] = mapped_column(String(120))
    hrac: Mapped[str] = mapped_column(String(8), index=True)
    target: Mapped[str] = mapped_column(String(16))
    crop: Mapped[str] = mapped_column(String(40))
    formulation: Mapped[str] = mapped_column(String(80))
    registered: Mapped[bool] = mapped_column(Boolean, default=True)


class Species(Timestamped, Base):
    """Tier 2 close-range label set, spec section 5.1."""

    __tablename__ = "species"

    latin: Mapped[str] = mapped_column(String(80), primary_key=True)
    local: Mapped[str] = mapped_column(String(80))
    common: Mapped[str] = mapped_column(String(80))
    cls: Mapped[str] = mapped_column(String(16))
    note: Mapped[str] = mapped_column(Text, default="")
