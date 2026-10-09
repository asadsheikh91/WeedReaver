"""Shared schema plumbing. JSON on the wire is camelCase, as both clients expect; Python
stays snake_case. Requests accept either spelling."""

from __future__ import annotations

from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

T = TypeVar("T")


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, from_attributes=True)


class Page(ApiModel, Generic[T]):
    items: list[T]
    total: int
    limit: int
    offset: int


class Message(ApiModel):
    message: str


class PointIn(ApiModel):
    x: float
    y: float


class LatLon(ApiModel):
    lat: float
    lon: float


class Timestamped(ApiModel):
    created_at: datetime | None = None
    updated_at: datetime | None = None
