"""Weed surfaces: what the aerial segmentation says about every point of a field.

A survey's segmentation output is held as a `WeedSurface`. Everything downstream (the 1/2/5 m
spray grid, abstention, treatment zones, the spray route, per-zone efficacy, exports) is
computed from this one interface, so swapping the source never touches the analysis.

Two implementations:

* `RasterSurface` - the production path. Per-pixel weed cover, class and (optionally) model
  confidence, resampled into the field's local metric frame. This is what a real
  segmentation model writes (see `app.pipeline.segmentation`).
* `PatchSurface` - a deterministic synthetic surface of lobed weed patches. It stands in for
  the model until one is trained, and it reproduces the demonstration data exactly.
"""

from __future__ import annotations

import hashlib
import json
import math
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from pathlib import Path

import numpy as np

from app.domain import geo, noise
from app.domain.enums import CLASS_CODES, WeedClass

CLASS_INDEX = {c: i for i, c in enumerate(CLASS_CODES)}


class WeedSurface(ABC):
    """Weed cover over a field, in the field's local frame (x east, y south, metres)."""

    boundary: list[geo.Pt]
    seed: int

    @abstractmethod
    def values(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        """Weed cover fraction in [0, 1] at each point."""

    @abstractmethod
    def classes(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        """Dominant weed class code (index into CLASS_CODES) at each point, ignoring cover."""

    def confidence(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray | None:
        """Model confidence in [0, 1] at each point, if the model provides one."""
        return None

    @property
    @abstractmethod
    def fingerprint(self) -> str:
        """Stable identity of the surface content, used as a cache key."""

    @property
    def frame(self) -> tuple[float, float, float, float]:
        """(min_x, min_y, width, height): the integer-aligned frame grids are built on."""
        b = geo.bounds(self.boundary)
        min_x, min_y = math.floor(b[0]), math.floor(b[1])
        return min_x, min_y, b[2] - min_x, b[3] - min_y


# --------------------------------------------------------------------------- patches


@dataclass(frozen=True)
class Patch:
    cx: float
    cy: float
    radius_m: float
    peak: float
    weed_class: WeedClass
    label: str
    stretch: float = 1.35

    def to_json(self) -> dict:
        return {
            "cx": self.cx, "cy": self.cy, "radiusM": self.radius_m, "peak": self.peak,
            "weedClass": self.weed_class.value, "label": self.label, "stretch": self.stretch,
        }

    @staticmethod
    def from_json(d: dict) -> "Patch":
        return Patch(
            cx=float(d["cx"]), cy=float(d["cy"]), radius_m=float(d["radiusM"]), peak=float(d["peak"]),
            weed_class=WeedClass(d["weedClass"]), label=str(d["label"]), stretch=float(d.get("stretch", 1.35)),
        )


@dataclass
class PatchSurface(WeedSurface):
    """Weeds are spatially aggregated (spec section 32, claim B), so the surface is a few lobed
    patches stretched along the drill direction, plus sparse sub-threshold background."""

    boundary: list[geo.Pt]
    patches: list[Patch]
    seed: int
    _fp: str | None = field(default=None, repr=False)

    def patch_value(self, p: Patch, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        dx = (xs - p.cx) / p.stretch
        dy = ys - p.cy
        d = np.hypot(dx, dy)
        angle = np.arctan2(dy, dx)
        ph = ord(p.label[0]) * 1.7
        r = p.radius_m * (1.0 + 0.22 * np.sin(3.0 * angle + ph) + 0.12 * np.sin(5.0 * angle - ph * 0.6))
        t = 1.0 - d / r
        return np.where(d < r, p.peak * t * t, 0.0)

    def scatter(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        n = noise.fbm(xs * 0.11, ys * 0.11, self.seed, 3)
        return np.maximum(0.0, (n - 0.62) * 0.28)

    def values(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        v = self.scatter(xs, ys)
        for p in self.patches:
            v = v + self.patch_value(p, xs, ys)
        return np.clip(v, 0.0, 1.0)

    def classes(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        if not self.patches:
            return np.full(np.shape(xs), CLASS_INDEX[WeedClass.GRASS], dtype=np.uint8)
        scores = np.stack(
            [self.patch_value(p, xs, ys) - np.hypot(xs - p.cx, ys - p.cy) * 0.0005 for p in self.patches]
        )
        best = np.argmax(scores, axis=0)
        lut = np.array([CLASS_INDEX[p.weed_class] for p in self.patches], dtype=np.uint8)
        return lut[best]

    def scaled(self, factors: dict[str, float], default: float) -> "PatchSurface":
        """A follow-up survey: every patch peak scaled by the fraction that survived."""
        return PatchSurface(
            boundary=self.boundary,
            patches=[replace(p, peak=min(1.0, max(0.0, p.peak * factors.get(p.label, default)))) for p in self.patches],
            seed=self.seed + 7,
        )

    @property
    def fingerprint(self) -> str:
        if self._fp is None:
            blob = json.dumps(
                {"b": self.boundary, "p": [p.to_json() for p in self.patches], "s": self.seed}, sort_keys=True
            )
            self._fp = "patch:" + hashlib.sha1(blob.encode()).hexdigest()
        return self._fp

    def to_spec(self) -> dict:
        return {"seed": self.seed, "patches": [p.to_json() for p in self.patches]}

    @staticmethod
    def from_spec(boundary: Sequence[geo.Pt], spec: dict) -> "PatchSurface":
        return PatchSurface(
            boundary=[tuple(p) for p in boundary],  # type: ignore[misc]
            patches=[Patch.from_json(p) for p in spec.get("patches", [])],
            seed=int(spec.get("seed", 0)),
        )


# --------------------------------------------------------------------------- rasters


@dataclass
class RasterSurface(WeedSurface):
    """Per-pixel segmentation output in the field's local frame.

    `cover[r, c]` is the weed cover fraction of the pixel whose top-left corner is
    (origin_x + c * pixel_m, origin_y + r * pixel_m). `classes[r, c]` indexes CLASS_CODES.
    `conf` is the model's per-pixel confidence; without it the grid falls back to a margin-
    ambiguity estimate.
    """

    boundary: list[geo.Pt]
    cover: np.ndarray
    class_map: np.ndarray
    origin_x: float
    origin_y: float
    pixel_m: float
    conf: np.ndarray | None = None
    seed: int = 0
    source: str = ""
    _fp: str | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        if self.cover.ndim != 2 or self.cover.shape != self.class_map.shape:
            raise ValueError("cover and classes must be 2-D arrays of the same shape")
        if self.conf is not None and self.conf.shape != self.cover.shape:
            raise ValueError("conf must match cover's shape")
        if self.pixel_m <= 0:
            raise ValueError("pixel_m must be positive")

    def _index(self, xs: np.ndarray, ys: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        cols = np.floor((xs - self.origin_x) / self.pixel_m).astype(np.int64)
        rows = np.floor((ys - self.origin_y) / self.pixel_m).astype(np.int64)
        h, w = self.cover.shape
        ok = (cols >= 0) & (rows >= 0) & (cols < w) & (rows < h)
        return np.clip(rows, 0, h - 1), np.clip(cols, 0, w - 1), ok

    def values(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        r, c, ok = self._index(xs, ys)
        return np.where(ok, np.clip(self.cover[r, c].astype(np.float64), 0.0, 1.0), 0.0)

    def classes(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        r, c, ok = self._index(xs, ys)
        return np.where(ok, self.class_map[r, c], CLASS_INDEX[WeedClass.CROP]).astype(np.uint8)

    def confidence(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray | None:
        if self.conf is None:
            return None
        r, c, ok = self._index(xs, ys)
        return np.where(ok, np.clip(self.conf[r, c].astype(np.float64), 0.0, 1.0), 1.0)

    @property
    def fingerprint(self) -> str:
        if self._fp is None:
            h = hashlib.sha1()
            h.update(np.ascontiguousarray(self.cover).tobytes())
            h.update(np.ascontiguousarray(self.class_map).tobytes())
            if self.conf is not None:
                h.update(np.ascontiguousarray(self.conf).tobytes())
            h.update(json.dumps([self.boundary, self.origin_x, self.origin_y, self.pixel_m]).encode())
            self._fp = "raster:" + h.hexdigest()
        return self._fp

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        arrays = {
            "cover": self.cover.astype(np.float32),
            "classes": self.class_map.astype(np.uint8),
            "transform": np.array([self.origin_x, self.origin_y, self.pixel_m], dtype=np.float64),
        }
        if self.conf is not None:
            arrays["conf"] = self.conf.astype(np.float32)
        with path.open("wb") as fh:
            np.savez_compressed(fh, **arrays)

    @staticmethod
    def load(path: Path, boundary: Sequence[geo.Pt], seed: int = 0) -> "RasterSurface":
        with np.load(path, allow_pickle=False) as data:
            t = data["transform"]
            return RasterSurface(
                boundary=[tuple(p) for p in boundary],  # type: ignore[misc]
                cover=data["cover"],
                class_map=data["classes"],
                origin_x=float(t[0]),
                origin_y=float(t[1]),
                pixel_m=float(t[2]),
                conf=data["conf"] if "conf" in data.files else None,
                seed=seed,
                source=str(path),
            )
