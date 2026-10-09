"""Planar geometry in a field's local metric frame.

Each field has its own tangent-plane frame: x east, y south, in metres, origin at the field's
north-west corner, anchored at (lat, lon). Over the few hundred metres a parcel spans the
equirectangular approximation is accurate to centimetres, and it is the conversion both
clients already use, so coordinates agree to the last digit across all three surfaces.
"""

from __future__ import annotations

import math
from collections.abc import Sequence

import numpy as np

from app.domain.enums import ACRE_SQM

Pt = tuple[float, float]
M_PER_DEG_LAT = 111_320.0


def area(poly: Sequence[Pt]) -> float:
    """Shoelace formula. Every area in the system comes from here."""
    if len(poly) < 3:
        return 0.0
    s = 0.0
    n = len(poly)
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % n]
        s += ax * by - bx * ay
    return abs(s / 2.0)


def signed_area(ring: Sequence[Pt]) -> float:
    s = 0.0
    n = len(ring)
    for i in range(n):
        ax, ay = ring[i]
        bx, by = ring[(i + 1) % n]
        s += ax * by - bx * ay
    return s / 2.0


def dist(a: Pt, b: Pt) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def perimeter(poly: Sequence[Pt], closed: bool = True) -> float:
    if len(poly) < 2:
        return 0.0
    s = sum(dist(poly[i], poly[i + 1]) for i in range(len(poly) - 1))
    if closed and len(poly) > 2:
        s += dist(poly[-1], poly[0])
    return s


def contains(poly: Sequence[Pt], x: float, y: float) -> bool:
    """Even-odd point in polygon, identical to the clients' implementation."""
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        ax, ay = poly[i]
        bx, by = poly[j]
        if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
            inside = not inside
        j = i
    return inside


def contains_many(poly: Sequence[Pt], xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    """Vectorised even-odd test over arrays of points."""
    inside = np.zeros(np.broadcast(xs, ys).shape, dtype=bool)
    n = len(poly)
    j = n - 1
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[j]
        if ay != by:
            crosses = (ay > ys) != (by > ys)
            xint = (bx - ax) * (ys - ay) / (by - ay) + ax
            inside ^= crosses & (xs < xint)
        j = i
    return inside


def centroid(poly: Sequence[Pt]) -> Pt:
    """Vertex mean, as the clients compute it (used for default scan positions)."""
    n = len(poly)
    return (sum(p[0] for p in poly) / n, sum(p[1] for p in poly) / n)


def bounds(poly: Sequence[Pt]) -> tuple[float, float, float, float]:
    xs = [p[0] for p in poly]
    ys = [p[1] for p in poly]
    return min(xs), min(ys), max(xs), max(ys)


def simplify(pts: Sequence[Pt], tolerance: float) -> list[Pt]:
    """Douglas-Peucker; the boundary walk applies it at 2 m."""
    pts = list(pts)
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        s, e = stack.pop()
        if e <= s + 1:
            continue
        ax, ay = pts[s]
        bx, by = pts[e]
        dx, dy = bx - ax, by - ay
        length = max(math.hypot(dx, dy), 1e-3)
        best, best_d = -1, 0.0
        for i in range(s + 1, e):
            px, py = pts[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / length
            if d > best_d:
                best_d, best = d, i
        if best_d > tolerance and best > 0:
            keep[best] = True
            stack.append((s, best))
            stack.append((best, e))
    return [p for p, k in zip(pts, keep) if k]


def simplify_ring(ring: Sequence[Pt], tolerance: float) -> list[Pt]:
    """Simplify a closed ring: split at the vertex farthest from the start, since a ring's
    first and last points coincide and Douglas-Peucker needs two distinct anchors."""
    ring = list(ring)
    if len(ring) < 4:
        return ring
    start = ring[0]
    far = max(range(len(ring)), key=lambda i: dist(ring[i], start))
    a = simplify(ring[: far + 1], tolerance)
    b = simplify(ring[far:] + [start], tolerance)
    return a[:-1] + b[:-1]


def bearing(frm: Pt, to: Pt) -> float:
    """Degrees clockwise from north for a vector in the local frame (y points south)."""
    deg = math.degrees(math.atan2(to[0] - frm[0], -(to[1] - frm[1])))
    return (deg + 360.0) % 360.0


_COMPASS = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")
_COMPASS_LONG = ("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")


def compass(deg: float, long: bool = False) -> str:
    i = min(7, max(0, int(((deg + 22.5) % 360.0) // 45)))
    return (_COMPASS_LONG if long else _COMPASS)[i]


def to_latlon(anchor_lat: float, anchor_lon: float, p: Pt) -> tuple[float, float]:
    d_lat = -p[1] / M_PER_DEG_LAT
    d_lon = p[0] / (M_PER_DEG_LAT * math.cos(math.radians(anchor_lat)))
    return anchor_lat + d_lat, anchor_lon + d_lon


def from_latlon(anchor_lat: float, anchor_lon: float, lat: float, lon: float) -> Pt:
    return (
        (lon - anchor_lon) * M_PER_DEG_LAT * math.cos(math.radians(anchor_lat)),
        -(lat - anchor_lat) * M_PER_DEG_LAT,
    )


def kanal_marla(acres: float) -> tuple[int, int]:
    """1 acre = 8 kanal = 160 marla."""
    k = math.floor(acres * 8)
    m = round((acres * 8 - k) * 20)
    return (k + 1, 0) if m == 20 else (k, m)


def acres(sqm: float) -> float:
    return sqm / ACRE_SQM


def _segments_cross(p1: Pt, p2: Pt, p3: Pt, p4: Pt) -> bool:
    def orient(a: Pt, b: Pt, c: Pt) -> float:
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    d1, d2 = orient(p3, p4, p1), orient(p3, p4, p2)
    d3, d4 = orient(p1, p2, p3), orient(p1, p2, p4)
    return ((d1 > 0) != (d2 > 0)) and ((d3 > 0) != (d4 > 0)) and 0 not in (d1, d2, d3, d4)


def is_simple(poly: Sequence[Pt]) -> bool:
    """True when no two non-adjacent edges cross. O(n²); boundaries are capped in size."""
    n = len(poly)
    if n < 3:
        return False
    edges = [(poly[i], poly[(i + 1) % n]) for i in range(n)]
    for i in range(n):
        for j in range(i + 1, n):
            if j == i + 1 or (i == 0 and j == n - 1):
                continue
            if _segments_cross(edges[i][0], edges[i][1], edges[j][0], edges[j][1]):
                return False
    return True


def dedupe_ring(poly: Sequence[Pt], eps: float = 0.05) -> list[Pt]:
    """Drop consecutive duplicate vertices and an explicit closing vertex."""
    out: list[Pt] = []
    for p in poly:
        if not out or dist(out[-1], p) > eps:
            out.append((float(p[0]), float(p[1])))
    if len(out) > 1 and dist(out[0], out[-1]) <= eps:
        out.pop()
    return out


def normalise_to_origin(poly: Sequence[Pt]) -> tuple[list[Pt], Pt]:
    """Shift so the bounding box's north-west corner is the origin. Returns (poly, shift)."""
    min_x, min_y, _, _ = bounds(poly)
    return [(round(x - min_x, 3), round(y - min_y, 3)) for x, y in poly], (min_x, min_y)
