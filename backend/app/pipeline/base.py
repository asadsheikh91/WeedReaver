"""Contracts between the job runner and the two heavy steps of a flight's processing.

    upload  ->  photogrammetry (OrthomosaicEngine)  ->  segmentation (SegmentationEngine)  ->  zoning

The runner owns everything around these steps: claiming the job, progress reporting, retries,
storing results, re-zoning and publishing. An engine only turns inputs into outputs.

Configure which engines run with WR_ORTHOMOSAIC_ENGINE / WR_SEGMENTATION_ENGINE, as dotted
paths "package.module:ClassName". Engines are constructed with no arguments.
"""

from __future__ import annotations

import importlib
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol, runtime_checkable

from app.analysis.surface import WeedSurface
from app.domain import geo

#: Report progress within the current step, 0..1. Cheap to call often; the runner throttles.
ProgressFn = Callable[[float], None]


class PipelineError(Exception):
    """Raise from an engine for a failure the operator should read (shown on the flight)."""


@dataclass
class FlightInputs:
    survey_id: str
    field_id: str
    role: str  # PRE | PLUS_14D | PLUS_28D
    image_paths: list[Path]
    image_count: int  # may exceed len(image_paths) for a simulated flight
    altitude_m: int
    sensor: str
    boundary: list[geo.Pt]  # field boundary, local metres (x east, y south)
    anchor_lat: float  # WGS84 of the local frame's origin; see app.domain.geo.to_latlon
    anchor_lon: float
    workdir: Path  # scratch space for this job; kept after success
    resume_from: float = 0.0  # progress already made in this step by an earlier attempt


@dataclass
class OrthomosaicResult:
    path: Path | None  # the stitched raster (e.g. GeoTIFF), or None when simulated
    gsd_cm: float
    pipeline: str  # human-readable provenance, e.g. "OpenDroneMap · NodeODM 3.5"
    meta: dict = field(default_factory=dict)


@dataclass
class SegmentationInputs:
    survey_id: str
    field_id: str
    role: str
    boundary: list[geo.Pt]
    gate: geo.Pt
    anchor_lat: float
    anchor_lon: float
    seed: int  # the field's landscape seed; deterministic engines may use it
    orthomosaic: OrthomosaicResult
    workdir: Path
    #: The pre-treatment survey's surface, when this is a follow-up flight.
    pre_surface: WeedSurface | None = None
    #: The +14 d surface, when this is the +28 d flight.
    plus14_surface: WeedSurface | None = None
    #: Observed state of each published zone (letter -> ZoneState), for follow-up flights.
    zone_states: dict[str, str] = field(default_factory=dict)
    #: The surface previously stored for this same survey (a re-run), if any.
    existing_surface: WeedSurface | None = None
    resume_from: float = 0.0


@dataclass
class SegmentationResult:
    surface: WeedSurface
    model_version: str


@runtime_checkable
class OrthomosaicEngine(Protocol):
    name: str
    #: False only for engines that can run without image files (the simulator).
    requires_images: bool

    def build(self, inputs: FlightInputs, progress: ProgressFn) -> OrthomosaicResult: ...


@runtime_checkable
class SegmentationEngine(Protocol):
    name: str

    def segment(self, inputs: SegmentationInputs, progress: ProgressFn) -> SegmentationResult: ...


def load_engine(dotted: str) -> object:
    module_name, _, cls_name = dotted.partition(":")
    if not module_name or not cls_name:
        raise ValueError(f"Engine path must look like 'package.module:ClassName', got {dotted!r}")
    cls = getattr(importlib.import_module(module_name), cls_name)
    return cls()
