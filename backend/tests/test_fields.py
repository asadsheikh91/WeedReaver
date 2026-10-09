from __future__ import annotations

import json

from tests.conftest import API

SAMPLE_KML = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Station plot register</name>
<Placemark><name>Tubewell killa</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
73.274100,31.896700,0
73.275340,31.896740,0
73.275400,31.895880,0
73.274120,31.895850,0
73.274100,31.896700,0
</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>"""


def test_list_fields_derived_facts(client, analyst):
    fields = {f["id"]: f for f in client.get(f"{API}/fields", headers=analyst).json()}
    assert set(fields) == {"F-047", "F-112", "F-203"}
    c47 = fields["F-047"]
    assert round(c47["area"]["acres"], 2) == 8.45 and (c47["area"]["kanal"], c47["area"]["marla"]) == (67, 12)
    assert c47["pressure"] == "HEAVY" and c47["zonesTotal"] == 5 and c47["surveyed"] is True
    assert c47["season"]["variety"] == "Akbar-2019" and c47["season"]["daysSinceSowing"] == 71
    assert [s["key"] for s in c47["loop"] if s["current"]] == ["route"]
    n = fields["F-203"]
    assert n["surveyed"] is False and n["pressure"] == "CLEAN" and n["nextSurvey"]["id"] == "S-05"
    assert [s["key"] for s in n["loop"] if s["current"]] == ["survey"]


def test_field_detail(client, analyst):
    d = client.get(f"{API}/fields/F-047", headers=analyst).json()
    assert d["counts"]["scans"] == 6 and d["counts"]["scansAwaitingReview"] == 2 and d["counts"]["treatments"] == 3
    assert d["upNext"].startswith("Plan the spray route · 5 zones")
    assert [s["id"] for s in d["surveys"]] == ["S-01", "S-02", "S-03"]
    assert client.get(f"{API}/fields/F-999", headers=analyst).status_code == 404


def test_create_field_from_latlon_idempotent(client, operator):
    body = {
        "clientId": "phone-field-0001", "name": "Tubewell killa", "captureMethod": "Walked", "simplifyToleranceM": 2,
        "boundaryLatLon": [{"lat": 31.8967, "lon": 73.2741}, {"lat": 31.89674, "lon": 73.27534},
                           {"lat": 31.89588, "lon": 73.2754}, {"lat": 31.89585, "lon": 73.27412}],
    }
    r = client.post(f"{API}/fields", json=body, headers=operator)
    assert r.status_code == 201, r.text
    f = r.json()
    assert f["id"].startswith("F-9") and f["captureMethod"] == "Walked" and 2.0 < f["area"]["acres"] < 3.5
    assert min(p["x"] for p in f["boundary"]) == 0 and min(p["y"] for p in f["boundary"]) == 0
    assert f["nextSurvey"]["role"] == "PRE" and f["season"]["season"] == "Rabi 2026-27"
    again = client.post(f"{API}/fields", json=body, headers=operator)
    assert again.status_code == 200 and again.json()["id"] == f["id"]


def test_create_field_rejects_bad_geometry(client, analyst):
    bowtie = {"name": "Bad", "boundary": [{"x": 0, "y": 0}, {"x": 100, "y": 100}, {"x": 100, "y": 0}, {"x": 0, "y": 100}],
              "lat": 31.9, "lon": 73.27}
    r = client.post(f"{API}/fields", json=bowtie, headers=analyst)
    assert r.status_code == 422 and "crosses itself" in r.json()["error"]["message"]
    tiny = {"name": "Tiny", "boundary": [{"x": 0, "y": 0}, {"x": 5, "y": 0}, {"x": 5, "y": 5}], "lat": 31.9, "lon": 73.27}
    assert client.post(f"{API}/fields", json=tiny, headers=analyst).status_code == 422
    neither = {"name": "None"}
    assert client.post(f"{API}/fields", json=neither, headers=analyst).status_code == 422


def test_parse_boundary_kml_and_geojson(client, analyst):
    r = client.post(f"{API}/fields/parse-boundary", files={"file": ("plots.kml", SAMPLE_KML, "application/vnd.google-earth.kml+xml")},
                    headers=analyst)
    assert r.status_code == 200, r.text
    p = r.json()
    assert p["sourceFormat"] == "KML" and p["name"] == "Tubewell killa" and p["vertices"] == 4
    gj = {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"name": "Plot"}, "geometry": {
        "type": "Polygon", "coordinates": [[[73.2741, 31.8967], [73.27534, 31.89674], [73.2754, 31.89588], [73.27412, 31.89585], [73.2741, 31.8967]]]}}]}
    r = client.post(f"{API}/fields/parse-boundary", files={"file": ("plot.geojson", json.dumps(gj), "application/geo+json")}, headers=analyst)
    assert r.status_code == 200 and r.json()["area"]["acres"] == p["area"]["acres"]
    bad = client.post(f"{API}/fields/parse-boundary", files={"file": ("x.kml", "<kml><!DOCTYPE x></kml>", "text/xml")}, headers=analyst)
    assert bad.status_code == 422


def test_boundary_edit_is_a_definition(client, operator, analyst):
    new = [{"x": 0, "y": 0}, {"x": 182, "y": 0}, {"x": 183, "y": 80}, {"x": 1, "y": 80}]
    r = client.patch(f"{API}/fields/F-203", json={"boundary": new}, headers=operator)
    assert r.status_code == 403 and r.json()["error"]["code"] == "ownership_violation"
    r = client.patch(f"{API}/fields/F-203", json={"boundary": new, "name": "North plot (re-surveyed)"}, headers=analyst)
    assert r.status_code == 200 and r.json()["name"] == "North plot (re-surveyed)"
    assert round(r.json()["area"]["sqm"]) == 182 * 80


def test_rename_needs_decider_or_capturer(client, operator, analyst):
    assert client.patch(f"{API}/fields/F-047", json={"name": "Chak 48"}, headers=operator).status_code == 403
    body = {"clientId": "phone-field-rename", "name": "Mine", "boundary": [{"x": 0, "y": 0}, {"x": 60, "y": 0},
            {"x": 60, "y": 60}, {"x": 0, "y": 60}], "lat": 31.9, "lon": 73.27, "captureMethod": "Drawn"}
    fid = client.post(f"{API}/fields", json=body, headers=operator).json()["id"]
    r = client.patch(f"{API}/fields/{fid}", json={"name": "Mine, renamed"}, headers=operator)
    assert r.status_code == 200 and r.json()["name"] == "Mine, renamed"


def test_archive_and_restore(client, analyst, operator):
    assert client.delete(f"{API}/fields/F-203", headers=operator).status_code == 403
    assert client.delete(f"{API}/fields/F-203", headers=analyst).status_code == 200
    assert "F-203" not in {f["id"] for f in client.get(f"{API}/fields", headers=analyst).json()}
    assert client.post(f"{API}/fields/F-203/restore", headers=analyst).status_code == 200
    assert "F-203" in {f["id"] for f in client.get(f"{API}/fields", headers=analyst).json()}


def test_boundary_geojson(client, analyst):
    r = client.get(f"{API}/fields/F-047/boundary.geojson", headers=analyst)
    g = r.json()
    ring = g["geometry"]["coordinates"][0]
    assert r.headers["content-type"].startswith("application/geo+json")
    assert ring[0] == ring[-1] and len(ring) == 7
    assert abs(ring[0][1] - 31.8942) < 0.002


def test_seasons(client, analyst):
    seasons = client.get(f"{API}/fields/F-047/seasons", headers=analyst).json()
    assert {s["season"] for s in seasons} >= {"Rabi 2026-27", "Rabi 2025-26"}
    r = client.patch(f"{API}/seasons/FS-047", json={"harvestDate": "2027-04-20"}, headers=analyst)
    assert r.status_code == 200 and r.json()["harvestDate"] == "2027-04-20"
    assert client.patch(f"{API}/seasons/FS-047", json={"harvestDate": "2026-01-01"}, headers=analyst).status_code == 422
