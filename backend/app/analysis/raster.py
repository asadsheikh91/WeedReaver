"""Rasterising a weed surface to the spray grid an applicator can act on (spec section 31).

A cell joins the prescription when *any part* of it crosses the threshold (its maximum
sub-sample), which is why coarser grids always flag more area: that trade is reported, not
hidden. A cell abstains where the model is unsure (spec section 26): it becomes a task for a
person, never a guess, and it is not sprayed.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from app.analysis.surface import CLASS_INDEX, WeedSurface
from app.domain import geo, noise
from app.domain.enums import CLASS_CODES, GRID_SIZES, WeedClass, band

FLAG_INSIDE = 1
FLAG_TREATED = 2
FLAG_ABSTAINED = 4


def _round_half_up(x: float) -> int:
    """JavaScript Math.round, so grid dimensions match the clients exactly."""
    return math.floor(x + 0.5)


@dataclass
class Grid:
    cols: int
    rows: int
    cell_m: int
    origin_x: float
    origin_y: float
    threshold_pct: float
    infest_pct: np.ndarray  # (rows, cols) float64
    class_idx: np.ndarray  # (rows, cols) uint8
    confidence: np.ndarray  # (rows, cols) float64
    inside: np.ndarray  # (rows, cols) bool
    treated: np.ndarray  # (rows, cols) bool
    abstained: np.ndarray  # (rows, cols) bool

    @property
    def total(self) -> int:
        return int(self.inside.sum())

    @property
    def flagged(self) -> int:
        return int(self.treated.sum())

    @property
    def abstained_count(self) -> int:
        return int(self.abstained.sum())

    @property
    def treated_fraction(self) -> float:
        t = self.total
        return 0.0 if t == 0 else self.flagged / t

    @property
    def treated_sqm(self) -> float:
        return float(self.flagged * self.cell_m * self.cell_m)

    def stats(self) -> dict:
        return {
            "cellMeters": self.cell_m,
            "thresholdPct": self.threshold_pct,
            "total": self.total,
            "flagged": self.flagged,
            "abstained": self.abstained_count,
            "treatedFraction": self.treated_fraction,
            "treatedSqm": self.treated_sqm,
            "treatedAcres": geo.acres(self.treated_sqm),
        }

    def cell_at(self, x: float, y: float) -> tuple[int, int] | None:
        c = math.floor((x - self.origin_x) / self.cell_m)
        r = math.floor((y - self.origin_y) / self.cell_m)
        if 0 <= c < self.cols and 0 <= r < self.rows:
            return c, r
        return None

    def cell_info(self, c: int, r: int) -> dict:
        infest = float(self.infest_pct[r, c])
        return {
            "col": c,
            "row": r,
            "ref": cell_ref(c, r),
            "inside": bool(self.inside[r, c]),
            "infestPct": round(infest, 2),
            "severity": band(infest).value,
            "weedClass": CLASS_CODES[int(self.class_idx[r, c])].value,
            "confidence": round(float(self.confidence[r, c]), 3),
            "abstained": bool(self.abstained[r, c]),
            "treated": bool(self.treated[r, c]),
            "x": self.origin_x + c * self.cell_m,
            "y": self.origin_y + r * self.cell_m,
            "sizeM": self.cell_m,
        }

    def compact(self) -> dict:
        """Columnar encoding: a 1 m grid of a 9-acre field is ~35k cells, so per-cell objects
        would be megabytes. Row-major arrays, one entry per cell."""
        flags = (
            self.inside.astype(np.uint8) * FLAG_INSIDE
            + self.treated.astype(np.uint8) * FLAG_TREATED
            + self.abstained.astype(np.uint8) * FLAG_ABSTAINED
        )
        letters = np.array([c.value[0] for c in CLASS_CODES])  # C, G, B
        return {
            "cols": self.cols,
            "rows": self.rows,
            "cellMeters": self.cell_m,
            "originX": self.origin_x,
            "originY": self.origin_y,
            "thresholdPct": self.threshold_pct,
            "stats": self.stats(),
            "encoding": {
                "order": "row-major",
                "flags": {"inside": FLAG_INSIDE, "treated": FLAG_TREATED, "abstained": FLAG_ABSTAINED},
                "classes": {c.value[0]: c.value for c in CLASS_CODES},
            },
            "infestPct": np.round(self.infest_pct, 1).ravel().tolist(),
            "confidence": np.round(self.confidence, 2).ravel().tolist(),
            "classes": "".join(letters[self.class_idx.ravel()].tolist()),
            "flags": flags.ravel().tolist(),
        }


def cell_ref(col: int, row: int) -> str:
    """Spreadsheet-style reference: A1, B1 ... Z1, AA1."""
    n = col
    s = ""
    while True:
        s = chr(65 + n % 26) + s
        n = n // 26 - 1
        if n < 0:
            break
    return f"{s}{row + 1}"


def rasterise(surface: WeedSurface, size: int, threshold_pct: float) -> Grid:
    if size not in GRID_SIZES:
        raise ValueError(f"grid size must be one of {GRID_SIZES}")
    min_x, min_y, width, height = surface.frame
    cell = float(size)
    cols = max(1, _round_half_up(width / cell))
    rows = max(1, _round_half_up(height / cell))
    n = 2 if size == 1 else 3

    cc, rr = np.meshgrid(np.arange(cols), np.arange(rows))
    x0 = min_x + cc * cell
    y0 = min_y + rr * cell
    inside = geo.contains_many(surface.boundary, x0 + cell / 2.0, y0 + cell / 2.0)

    ix = np.nonzero(inside.ravel())[0]
    m = ix.size
    infest = np.zeros(rows * cols, dtype=np.float64)
    cls = np.full(rows * cols, CLASS_INDEX[WeedClass.CROP], dtype=np.uint8)
    conf = np.ones(rows * cols, dtype=np.float64)
    treated = np.zeros(rows * cols, dtype=bool)
    abstained = np.zeros(rows * cols, dtype=bool)

    if m:
        bx = x0.ravel()[ix]
        by = y0.ravel()[ix]
        offs = (np.arange(n) + 0.5) / n * cell
        # (m, n*n) sub-samples in the clients' order: sy outer, sx inner.
        sx = np.tile(offs, n)
        sy = np.repeat(offs, n)
        px = bx[:, None] + sx[None, :]
        py = by[:, None] + sy[None, :]
        vals = surface.values(px, py)
        mean = vals.mean(axis=1)
        mx = vals.max(axis=1)
        pct = mean * 100.0
        dominant = surface.classes(bx + cell / 2.0, by + cell / 2.0)
        cls_inside = np.where(pct < 4.0, CLASS_INDEX[WeedClass.CROP], dominant).astype(np.uint8)

        model_conf = surface.confidence(px, py)
        if model_conf is not None:
            c_in = model_conf.mean(axis=1)
        else:
            # Without a model confidence, uncertainty is highest where crop and weed canopy
            # mix: the patch margin, not its core.
            ambiguity = np.clip(1.0 - np.abs(mean - 0.13) / 0.09, 0.0, 1.0)
            rng = noise.mulberry32(surface.seed * 31 + size, m)
            c_in = np.clip(0.97 - ambiguity * 0.42 - rng * 0.16, 0.35, 0.99)
        ab = (pct > 6.0) & (c_in < 0.5)
        tr = (~ab) & (mx * 100.0 >= threshold_pct)

        infest[ix] = pct
        cls[ix] = cls_inside
        conf[ix] = c_in
        abstained[ix] = ab
        treated[ix] = tr

    shape = (rows, cols)
    return Grid(
        cols=cols, rows=rows, cell_m=size, origin_x=float(min_x), origin_y=float(min_y),
        threshold_pct=float(threshold_pct),
        infest_pct=infest.reshape(shape), class_idx=cls.reshape(shape), confidence=conf.reshape(shape),
        inside=inside.reshape(shape), treated=treated.reshape(shape), abstained=abstained.reshape(shape),
    )


def prescription_rings(grid: Grid) -> list[list[list[geo.Pt]]]:
    """Outline of the prescription (treated cells) as polygons, for exports and overlays."""
    from app.analysis.polygonize import mask_to_polygons

    return mask_to_polygons(grid.treated, grid.origin_x, grid.origin_y, grid.cell_m)
