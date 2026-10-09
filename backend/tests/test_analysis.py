"""The analysis engine reproduces the clients' numbers exactly (reference values were produced by
running the dashboard's own TypeScript implementation on the seed data)."""

from __future__ import annotations

import numpy as np
import pytest

from app.analysis.efficacy import zone_efficacy
from app.analysis.polygonize import mask_to_polygons, polygons_area
from app.analysis.raster import cell_ref, rasterise
from app.analysis.surface import PatchSurface, RasterSurface
from app.analysis.zones import compute_zones, route_legs
from app.domain import geo, noise
from app.seed.demo import EFFICACY_FACTORS, FIELDS, PATCHES


def surface(fid: str) -> PatchSurface:
    f = next(x for x in FIELDS if x["id"] == fid)
    return PatchSurface([tuple(map(float, p)) for p in f["boundary"]], PATCHES[fid], f["landscape"]["seed"])


# (cols, rows, total, flagged, abstained, sumInfest) from the web client
WEB = {
    ("F-047", 1): (245, 143, 34183, 2441, 217, 92321.77491553828),
    ("F-047", 2): (123, 72, 8530, 688, 43, 23063.179529634104),
    ("F-047", 5): (49, 29, 1377, 135, 12, 3700.721114493027),
    ("F-112", 1): (172, 86, 13503, 566, 82, 22935.300919588404),
    ("F-112", 2): (86, 43, 3376, 170, 14, 5729.9868840200315),
    ("F-112", 5): (34, 17, 544, 34, 4, 921.9131644413725),
    ("F-203", 2): (92, 45, 4005, 11, 3, 2006.6764123427643),
}


@pytest.mark.parametrize("key", list(WEB))
def test_grid_matches_web_client(key):
    fid, size = key
    g = rasterise(surface(fid), size, 10)
    cols, rows, total, flagged, abstained, s = WEB[key]
    assert (g.cols, g.rows, g.total, g.flagged, g.abstained_count) == (cols, rows, total, flagged, abstained)
    assert float(g.infest_pct.sum()) == pytest.approx(s, rel=1e-12)


def test_zones_match_web_client():
    zs = compute_zones(surface("F-047"), (1, 84), 10)
    assert [(z.letter, z.area_sqm, z.severity.value, z.distance_m) for z in zs] == [
        ("E", 138, "MODERATE", 31), ("A", 988, "HEAVY", 64), ("D", 238, "MODERATE", 75),
        ("B", 502, "HEAVY", 78), ("C", 503, "HEAVY", 76),
    ]
    for z in zs:  # the polygons are the exact union of the zone's cells
        assert polygons_area(z.polygons) == pytest.approx(z.area_sqm)


def test_areas_from_vertices():
    acres = {f["id"]: geo.acres(geo.area([tuple(p) for p in f["boundary"]])) for f in FIELDS}
    assert round(acres["F-047"], 2) == 8.45
    assert round(acres["F-112"], 2) == 3.34
    assert round(acres["F-203"], 2) == 3.96
    assert geo.kanal_marla(acres["F-047"]) == (67, 12)


def test_coarser_grid_flags_more_area():
    s = surface("F-047")
    fractions = [rasterise(s, m, 10).treated_fraction for m in (1, 2, 5)]
    assert fractions[0] < fractions[1] < fractions[2]


def test_threshold_monotonic():
    s = surface("F-047")
    flagged = [rasterise(s, 2, t).flagged for t in (5, 10, 20, 40)]
    assert flagged == sorted(flagged, reverse=True)


def test_mulberry32_and_hash_match_js():
    # Values computed with the dashboard's noise.ts
    assert noise.mulberry32(42, 3).tolist() == pytest.approx([0.6011037519201636, 0.44829055899754167, 0.8524657934904099])
    assert float(noise.hash2(np.array([3]), np.array([-7]), 4711)[0]) == pytest.approx(0.11088057985104648, abs=1e-15)
    assert float(noise.hash2(np.array([-123456]), np.array([98765]), 2031)[0]) == pytest.approx(0.6741721842783374, abs=1e-15)
    assert float(noise.fbm(np.array([12.34]), np.array([-5.6]), 1123, 3)[0]) == pytest.approx(0.5173386602089225, abs=1e-12)


def test_efficacy_surfaces_the_failed_zone():
    pre = surface("F-047")
    post = pre.scaled(EFFICACY_FACTORS, 0.5)
    by = {z.letter: zone_efficacy(z.letter, z.polygons, pre, post) for z in compute_zones(pre, (1, 84), 10)}
    assert by["A"].efficacy_pct >= 75
    assert by["C"].efficacy_pct < 40 and by["C"].inspect


def test_polygonize_holes_and_diagonals():
    m = np.zeros((5, 5), dtype=bool)
    m[0:5, 0:5] = True
    m[2, 2] = False  # a hole
    polys = mask_to_polygons(m, 0, 0, 1)
    assert len(polys) == 1 and len(polys[0]) == 2
    assert polygons_area(polys) == 24
    d = np.zeros((2, 2), dtype=bool)
    d[0, 0] = d[1, 1] = True  # touching only at a corner: two separate rings
    assert len(mask_to_polygons(d, 0, 0, 1)) == 2


def test_raster_surface_zones_and_confidence():
    b = [(0.0, 0.0), (60.0, 0.0), (60.0, 40.0), (0.0, 40.0)]
    cover = np.zeros((80, 120), dtype=np.float32)
    cover[10:30, 20:50] = 0.6  # one dense block at 0.5 m pixels
    classes = np.zeros_like(cover, dtype=np.uint8)
    classes[10:30, 20:50] = 1
    conf = np.full_like(cover, 0.9)
    conf[10:12, 20:50] = 0.3
    s = RasterSurface(b, cover, classes, 0.0, 0.0, 0.5, conf=conf)
    g = rasterise(s, 1, 10)
    assert g.flagged > 0 and g.abstained_count > 0
    zs = compute_zones(s, (0, 0), 10)
    assert len(zs) == 1 and zs[0].letter == "A" and zs[0].dominant_class.value == "GRASS"
    assert zs[0].area_sqm == 150


def test_route_and_cell_refs():
    legs = route_legs([("A", 10, 0), ("B", 0, 30)], (0, 0))
    assert [leg["letter"] for leg in legs] == ["A", "B"]
    assert legs[0]["compass"] == "E"
    assert cell_ref(0, 0) == "A1" and cell_ref(25, 4) == "Z5" and cell_ref(26, 0) == "AA1"
