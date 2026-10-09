"""Segmentation: orthomosaic -> weed surface (crop canopy / grass weed / broadleaf weed).

`SimulatedSegmentationEngine` produces deterministic synthetic surfaces so the system works
end-to-end without a trained model. `ModelSegmentationEngine` is where the trained model goes.
"""

from __future__ import annotations

import math
import random
import time

from app.analysis.surface import Patch, PatchSurface, RasterSurface, WeedSurface
from app.core.config import get_settings
from app.domain import geo
from app.domain.enums import WeedClass, ZoneState
from app.pipeline.base import PipelineError, ProgressFn, SegmentationInputs, SegmentationResult

SIMULATED_MODEL = "simulated-patches v1"


def _sleep_progress(progress: ProgressFn, share: float, start: float) -> None:
    total = max(0.0, get_settings().simulated_pipeline_seconds) * share
    steps = 10
    remaining = int(round(steps * (1.0 - start)))
    for i in range(1, remaining + 1):
        if total:
            time.sleep(total / steps)
        progress(start + (1.0 - start) * i / max(1, remaining))


def synthetic_patches(boundary: list[geo.Pt], seed: int) -> list[Patch]:
    """A plausible infestation for a parcel that has no model output yet: two to five lobed
    patches inside the boundary, aggregated as Phalaris minor is behind a seed drill."""
    rng = random.Random(seed * 7919 + 17)
    min_x, min_y, max_x, max_y = geo.bounds(boundary)
    area = geo.area(boundary)
    n = 2 + int(min(3, area // 9000))
    patches: list[Patch] = []
    attempts = 0
    while len(patches) < n and attempts < 400:
        attempts += 1
        cx = rng.uniform(min_x + 8, max_x - 8) if max_x - min_x > 16 else (min_x + max_x) / 2
        cy = rng.uniform(min_y + 8, max_y - 8) if max_y - min_y > 16 else (min_y + max_y) / 2
        if not geo.contains(boundary, cx, cy):
            continue
        if any(math.hypot(cx - p.cx, cy - p.cy) < p.radius_m * 1.6 for p in patches):
            continue
        radius = rng.uniform(8, min(20, max(9, math.sqrt(area) / 6)))
        patches.append(
            Patch(
                cx=round(cx, 1),
                cy=round(cy, 1),
                radius_m=round(radius, 1),
                peak=round(rng.uniform(0.32, 0.82) if len(patches) < 2 else rng.uniform(0.18, 0.6), 2),
                weed_class=WeedClass.GRASS if rng.random() < 0.65 else WeedClass.BROADLEAF,
                label=chr(65 + len(patches)),
                stretch=round(rng.uniform(1.1, 1.6), 2),
            )
        )
    return patches


class SimulatedSegmentationEngine:
    """Deterministic stand-in for the aerial model.

    * Pre-treatment flight: reuses the surface already on record for this survey (seed data),
      otherwise synthesises patches from the field's seed.
    * Follow-up flight: the pre-treatment surface with each zone's weed cover reduced according
      to what the phone reported. Zones marked treated mostly respond; untreated zones barely
      move. A +28 d flight continues the +14 d trend.
    """

    name = "Simulated segmentation"

    def segment(self, inputs: SegmentationInputs, progress: ProgressFn) -> SegmentationResult:
        _sleep_progress(progress, 0.30, inputs.resume_from)
        if isinstance(inputs.existing_surface, PatchSurface):
            return SegmentationResult(inputs.existing_surface, SIMULATED_MODEL)

        if inputs.role == "PRE":
            if isinstance(inputs.pre_surface, PatchSurface):
                return SegmentationResult(inputs.pre_surface, SIMULATED_MODEL)
            surface = PatchSurface(boundary=list(inputs.boundary), patches=synthetic_patches(inputs.boundary, inputs.seed), seed=inputs.seed)
            return SegmentationResult(surface, SIMULATED_MODEL)

        pre = inputs.pre_surface
        if not isinstance(pre, PatchSurface):
            raise PipelineError("The simulated model needs a simulated pre-treatment surface to derive a follow-up")
        rng = random.Random(f"{inputs.survey_id}:{inputs.seed}")
        factors: dict[str, float] = {}
        plus14 = inputs.plus14_surface if isinstance(inputs.plus14_surface, PatchSurface) else None
        for p in pre.patches:
            if inputs.role == "PLUS_28D" and plus14 is not None:
                q = next((x for x in plus14.patches if x.label == p.label), None)
                base = (q.peak / p.peak) if q and p.peak > 0 else 0.5
                factors[p.label] = round(base * 0.85, 4)
                continue
            state = inputs.zone_states.get(p.label)
            treated = state in (ZoneState.TREATED, ZoneState.RESURVEYED)
            factors[p.label] = round(rng.uniform(0.12, 0.38) if treated else rng.uniform(0.8, 1.0), 4)
        return SegmentationResult(pre.scaled(factors, 0.5), SIMULATED_MODEL)


class ModelSegmentationEngine:
    """The trained aerial model (spec: wr-seg-deeplabv3p-r50). To implement.

    Read `inputs.orthomosaic.path`, run the model, and return a RasterSurface in the field's
    local frame (x east, y south, metres from the anchor; see app.domain.geo.from_latlon to map
    the orthomosaic's georeferencing into it):

        cover   float32 (H, W)  weed cover fraction per pixel, 0..1
        classes uint8   (H, W)  0 crop, 1 grass weed, 2 broadleaf weed
        conf    float32 (H, W)  model confidence 0..1 (optional; enables real abstention)
        origin_x, origin_y      local metres of pixel (0, 0)'s top-left corner
        pixel_m                 pixel size in metres (e.g. 0.25 after downsampling)

        return SegmentationResult(
            RasterSurface(boundary=inputs.boundary, cover=cover, class_map=classes,
                          origin_x=ox, origin_y=oy, pixel_m=px, conf=conf, seed=inputs.seed),
            model_version="wr-seg-deeplabv3p-r50 v0.5.0")

    The runner stores it, rasterises it to the 1/2/5 m grids, extracts zones and publishes them.
    Select it with WR_SEGMENTATION_ENGINE=app.pipeline.segmentation:ModelSegmentationEngine.
    """

    name = "Aerial segmentation model"

    def segment(self, inputs: SegmentationInputs, progress: ProgressFn) -> SegmentationResult:
        raise PipelineError("Segmentation model not implemented yet (ModelSegmentationEngine)")


def surface_kind(surface: WeedSurface) -> str:
    return "patches" if isinstance(surface, PatchSurface) else "raster" if isinstance(surface, RasterSurface) else "custom"
