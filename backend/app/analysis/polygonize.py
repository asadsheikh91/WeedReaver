"""Cell masks to polygons: the exact outline of a set of grid cells.

Used for treatment-zone geometry and prescription outlines in exports. Each cell contributes
four directed edges with its interior on one side; edges shared by two cells cancel, and the
remaining edges chain into rings. At a vertex where two cells touch only diagonally, the walk
turns toward the interior so diagonal neighbours become separate rings (a sprayer section
cannot pass through a point).
"""

from __future__ import annotations

import numpy as np

from app.domain import geo

Ring = list[geo.Pt]
Polygon = list[Ring]  # [exterior, *holes]

_Vertex = tuple[int, int]


def _left(d: tuple[int, int]) -> tuple[int, int]:
    return (-d[1], d[0])


def _right(d: tuple[int, int]) -> tuple[int, int]:
    return (d[1], -d[0])


def mask_to_rings_lattice(mask: np.ndarray) -> list[list[_Vertex]]:
    """Boundary rings in lattice coordinates (vertex (c, r) is a cell corner)."""
    rows, cols = mask.shape
    edges: dict[_Vertex, list[_Vertex]] = {}
    count = 0
    padded = np.pad(mask.astype(bool), 1)
    rs, cs = np.nonzero(mask)
    for r, c in zip(rs.tolist(), cs.tolist()):
        pr, pc = r + 1, c + 1
        # Interior on the (-dy, dx) side; emit only edges facing an empty neighbour.
        if not padded[pr - 1, pc]:
            edges.setdefault((c, r), []).append((c + 1, r))
            count += 1
        if not padded[pr, pc + 1]:
            edges.setdefault((c + 1, r), []).append((c + 1, r + 1))
            count += 1
        if not padded[pr + 1, pc]:
            edges.setdefault((c + 1, r + 1), []).append((c, r + 1))
            count += 1
        if not padded[pr, pc - 1]:
            edges.setdefault((c, r + 1), []).append((c, r))
            count += 1

    rings: list[list[_Vertex]] = []
    while edges:
        start = next(iter(edges))
        ring = [start]
        prev = start
        cur = edges[start].pop()
        if not edges[start]:
            del edges[start]
        guard = count + 4
        while cur != start and guard > 0:
            guard -= 1
            ring.append(cur)
            options = edges.get(cur)
            if not options:
                break  # malformed; cannot happen for a cell mask
            d = (cur[0] - prev[0], cur[1] - prev[1])
            if len(options) == 1:
                nxt = options.pop()
            else:
                ranked = sorted(
                    options,
                    key=lambda v: {_left(d): 0, d: 1, _right(d): 2}.get((v[0] - cur[0], v[1] - cur[1]), 3),
                )
                nxt = ranked[0]
                options.remove(nxt)
            if not options:
                del edges[cur]
            prev, cur = cur, nxt
        rings.append(_drop_collinear(ring))
    return rings


def _drop_collinear(ring: list[_Vertex]) -> list[_Vertex]:
    n = len(ring)
    if n < 4:
        return ring
    out = []
    for i in range(n):
        a, b, c = ring[i - 1], ring[i], ring[(i + 1) % n]
        if (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]) != 0:
            out.append(b)
    return out


def mask_to_polygons(mask: np.ndarray, origin_x: float, origin_y: float, cell_m: float) -> list[Polygon]:
    """Polygons (exterior + holes) in local metres for the true cells of `mask`."""
    lattice = mask_to_rings_lattice(mask)
    rings = [[(origin_x + c * cell_m, origin_y + r * cell_m) for c, r in ring] for ring in lattice]
    exteriors: list[Ring] = []
    holes: list[Ring] = []
    for ring in rings:
        if len(ring) < 3:
            continue
        (exteriors if geo.signed_area(ring) > 0 else holes).append(ring)

    polygons: list[Polygon] = [[e] for e in exteriors]
    ext_areas = [geo.area(e) for e in exteriors]
    for h in holes:
        # A point just inside the hole: the midpoint of its first edge, nudged to the side
        # away from the cells (holes run with the empty side on the right).
        (ax, ay), (bx, by) = h[0], h[1]
        dx, dy = bx - ax, by - ay
        length = max(abs(dx) + abs(dy), 1e-9)
        nx, ny = dy / length, -dx / length
        px, py = (ax + bx) / 2 + nx * cell_m * 0.25, (ay + by) / 2 + ny * cell_m * 0.25
        best = None
        for i, e in enumerate(exteriors):
            if geo.contains(e, px, py) and (best is None or ext_areas[i] < ext_areas[best]):
                best = i
        if best is not None:
            polygons[best].append(h)
    return polygons


def polygons_area(polygons: list[Polygon]) -> float:
    total = 0.0
    for poly in polygons:
        total += geo.area(poly[0]) - sum(geo.area(h) for h in poly[1:])
    return total


def footprint_mask(polygons: list[Polygon], step: float = 1.0) -> tuple[np.ndarray, np.ndarray]:
    """Lattice points (cell centres at `step` spacing) that fall inside the polygons."""
    if not polygons:
        return np.zeros(0), np.zeros(0)
    xs_all = [p[0] for poly in polygons for p in poly[0]]
    ys_all = [p[1] for poly in polygons for p in poly[0]]
    x0, x1 = min(xs_all), max(xs_all)
    y0, y1 = min(ys_all), max(ys_all)
    gx = np.arange(x0 + step / 2, x1, step)
    gy = np.arange(y0 + step / 2, y1, step)
    if gx.size == 0 or gy.size == 0:
        return np.zeros(0), np.zeros(0)
    xx, yy = np.meshgrid(gx, gy)
    xx, yy = xx.ravel(), yy.ravel()
    keep = np.zeros(xx.shape, dtype=bool)
    for poly in polygons:
        inside = geo.contains_many(poly[0], xx, yy)
        for h in poly[1:]:
            inside &= ~geo.contains_many(h, xx, yy)
        keep |= inside
    return xx[keep], yy[keep]
