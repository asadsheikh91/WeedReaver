"""Treatment zones and the nearest-first spray route.

Zones are the contiguous prescription area: what an operator walks to and treats. They are
ordered by a greedy nearest-neighbour walk from the field gate, deliberately not a globally
optimal tour: a person on foot with a knapsack needs the shortest next leg from wherever they
are standing.
"""

from __future__ import annotations

import math
from collections import deque
from collections.abc import Sequence
from dataclasses import dataclass, field, replace

import numpy as np

from app.analysis.polygonize import Polygon, mask_to_polygons
from app.analysis.surface import PatchSurface, WeedSurface
from app.domain import geo
from app.domain.enums import CLASS_CODES, Severity, WeedClass, band

MIN_ZONE_SQM = 4


@dataclass
class ZoneGeom:
    letter: str
    severity: Severity
    dominant_class: WeedClass
    area_sqm: int
    cell_count: int
    cx: float
    cy: float
    radius_m: float
    mean_infest_pct: float
    polygons: list[Polygon] = field(default_factory=list)
    distance_m: int = 0
    route_order: int = 0

    @property
    def label(self) -> str:
        return f"Zone {self.letter}"


@dataclass(frozen=True)
class PriorZone:
    """A zone already published, used to keep letters stable when zones are recomputed."""

    letter: str
    cx: float
    cy: float
    radius_m: float


def letter_for(i: int) -> str:
    s = ""
    n = i
    while True:
        s = chr(65 + n % 26) + s
        n = n // 26 - 1
        if n < 0:
            return s


def compute_zones(
    surface: WeedSurface,
    gate: geo.Pt,
    threshold_pct: float,
    prior: Sequence[PriorZone] = (),
) -> list[ZoneGeom]:
    if isinstance(surface, PatchSurface):
        zones = _patch_zones(surface, threshold_pct)
    else:
        zones = _component_zones(surface, threshold_pct, prior)
    return order_route(zones, gate)


def _patch_zones(s: PatchSurface, threshold_pct: float) -> list[ZoneGeom]:
    """One zone per significant patch: the 1 m lattice around it where the patch crosses the
    threshold. Matches the field app and dashboard implementation exactly."""
    thr = threshold_pct / 100.0
    out: list[ZoneGeom] = []
    for p in s.patches:
        if p.peak < 0.30:
            continue
        reach = math.floor(p.radius_m * 1.4 * p.stretch) + 2
        offs = np.arange(-reach, reach + 1, dtype=np.float64)
        yy, xx = np.meshgrid(offs, offs, indexing="ij")
        xs = p.cx + xx
        ys = p.cy + yy
        inside = geo.contains_many(s.boundary, xs, ys)
        hit = inside & (s.patch_value(p, xs, ys) >= thr)
        count = int(hit.sum())
        if count == 0:
            continue
        mean = float(s.values(xs[hit], ys[hit]).sum()) / count * 100.0
        polygons = mask_to_polygons(hit, p.cx - reach - 0.5, p.cy - reach - 0.5, 1.0)
        out.append(
            ZoneGeom(
                letter=p.label,
                severity=band(mean * 1.25),
                dominant_class=p.weed_class,
                area_sqm=count,
                cell_count=round(count / 4),
                cx=p.cx,
                cy=p.cy,
                radius_m=math.sqrt(count / math.pi),
                mean_infest_pct=mean,
                polygons=polygons,
            )
        )
    return out


def _components(mask: np.ndarray) -> list[np.ndarray]:
    """8-connected components of a boolean mask, as arrays of flat indices."""
    rows, cols = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    flat = mask.ravel()
    comps: list[np.ndarray] = []
    for start in np.flatnonzero(flat):
        if seen.flat[start]:
            continue
        q = deque([int(start)])
        seen.flat[start] = True
        members = []
        while q:
            i = q.popleft()
            members.append(i)
            r, c = divmod(i, cols)
            for dr in (-1, 0, 1):
                rr = r + dr
                if rr < 0 or rr >= rows:
                    continue
                for dc in (-1, 0, 1):
                    cc = c + dc
                    if (dr or dc) and 0 <= cc < cols:
                        j = rr * cols + cc
                        if flat[j] and not seen.flat[j]:
                            seen.flat[j] = True
                            q.append(j)
        comps.append(np.array(members, dtype=np.int64))
    return comps


def _component_zones(s: WeedSurface, threshold_pct: float, prior: Sequence[PriorZone]) -> list[ZoneGeom]:
    """Zones from any surface: 8-connected regions of the 1 m lattice above the threshold."""
    min_x, min_y, width, height = s.frame
    cols, rows = max(1, math.ceil(width)), max(1, math.ceil(height))
    cc, rr = np.meshgrid(np.arange(cols), np.arange(rows))
    xs = min_x + cc + 0.5
    ys = min_y + rr + 0.5
    inside = geo.contains_many(s.boundary, xs, ys)
    vals = np.where(inside, s.values(xs, ys), 0.0)
    mask = inside & (vals * 100.0 >= threshold_pct)
    classes = s.classes(xs, ys)

    found: list[tuple[np.ndarray, ZoneGeom]] = []
    for comp in _components(mask):
        if comp.size < MIN_ZONE_SQM:
            continue
        r_idx, c_idx = np.divmod(comp, cols)
        sub = np.zeros_like(mask)
        sub[r_idx, c_idx] = True
        count = int(comp.size)
        mean = float(vals.ravel()[comp].mean()) * 100.0
        cls_counts = np.bincount(classes.ravel()[comp], minlength=len(CLASS_CODES))
        cls_counts[0] = 0  # a weed zone's class is never "crop"
        dominant = CLASS_CODES[int(np.argmax(cls_counts))] if cls_counts.sum() else WeedClass.GRASS
        cx = float(xs.ravel()[comp].mean())
        cy = float(ys.ravel()[comp].mean())
        zone = ZoneGeom(
            letter="",
            severity=band(mean * 1.25),
            dominant_class=dominant,
            area_sqm=count,
            cell_count=round(count / 4),
            cx=round(cx, 2),
            cy=round(cy, 2),
            radius_m=math.sqrt(count / math.pi),
            mean_infest_pct=mean,
            polygons=mask_to_polygons(sub, float(min_x), float(min_y), 1.0),
        )
        found.append((sub, zone))

    # Keep letters stable across re-zoning: a new region inherits the letter of the published
    # zone whose centre it covers (or the nearest one close enough); the rest get fresh letters
    # in order of size.
    used: set[str] = set()
    for sub, z in sorted(found, key=lambda t: -t[1].area_sqm):
        best: PriorZone | None = None
        best_d = float("inf")
        for pz in prior:
            if pz.letter in used:
                continue
            c = math.floor(pz.cx - min_x)
            r = math.floor(pz.cy - min_y)
            covers = 0 <= r < rows and 0 <= c < cols and bool(sub[r, c])
            d = 0.0 if covers else math.hypot(pz.cx - z.cx, pz.cy - z.cy)
            if (covers or d <= max(z.radius_m, pz.radius_m) * 1.5 + 5.0) and d < best_d:
                best, best_d = pz, d
        if best is not None:
            z.letter = best.letter
            used.add(best.letter)
    reserved = used | {pz.letter for pz in prior}
    i = 0
    for _, z in sorted(found, key=lambda t: -t[1].area_sqm):
        if z.letter:
            continue
        while letter_for(i) in reserved:
            i += 1
        z.letter = letter_for(i)
        reserved.add(z.letter)
    return [z for _, z in found]


def order_route(zones: Sequence[ZoneGeom], start: geo.Pt) -> list[ZoneGeom]:
    """Greedy nearest-neighbour chain from `start`. Returns copies with distance and order."""
    remaining = list(zones)
    ordered: list[ZoneGeom] = []
    at = start
    while remaining:
        best_i = min(range(len(remaining)), key=lambda i: geo.dist((remaining[i].cx, remaining[i].cy), at))
        z = remaining.pop(best_i)
        d = geo.dist((z.cx, z.cy), at)
        ordered.append(replace(z, distance_m=round(d), route_order=len(ordered) + 1))
        at = (z.cx, z.cy)
    return ordered


def route_legs(points: Sequence[tuple[str, float, float]], start: geo.Pt) -> list[dict]:
    """Nearest-first legs through (letter, x, y) stops, from where the operator stands."""
    remaining = list(points)
    legs: list[dict] = []
    at = start
    total = 0.0
    while remaining:
        best_i = min(range(len(remaining)), key=lambda i: geo.dist((remaining[i][1], remaining[i][2]), at))
        letter, x, y = remaining.pop(best_i)
        d = geo.dist((x, y), at)
        total += d
        b = geo.bearing(at, (x, y))
        legs.append(
            {
                "letter": letter,
                "order": len(legs) + 1,
                "fromX": at[0],
                "fromY": at[1],
                "toX": x,
                "toY": y,
                "distanceM": round(d, 1),
                "cumulativeM": round(total, 1),
                "bearingDeg": round(b, 1),
                "compass": geo.compass(b),
                "instruction": f"Walk {round(d)} m {geo.compass(b, long=True)} to Zone {letter}",
            }
        )
        at = (x, y)
    return legs
