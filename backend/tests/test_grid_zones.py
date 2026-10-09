from __future__ import annotations

from tests.conftest import API


def test_grid_compact_matches_engine(client, analyst):
    r = client.get(f"{API}/fields/F-047/grid?size=2", headers={**analyst, "Accept-Encoding": "gzip"})
    assert r.status_code == 200
    g = r.json()
    assert (g["cols"], g["rows"], g["cellMeters"], g["thresholdPct"]) == (123, 72, 2, 10)
    assert len(g["infestPct"]) == len(g["flags"]) == len(g["classes"]) == 123 * 72
    assert g["stats"]["flagged"] == 688 and g["stats"]["abstained"] == 43
    assert sum(1 for f in g["flags"] if f & 2) == 688
    assert set(g["classes"]) <= {"C", "G", "B"}


def test_grid_stats_follow_threshold_and_size(client, analyst):
    at = lambda t, s=2: client.get(f"{API}/fields/F-047/grid/stats?size={s}&threshold={t}", headers=analyst).json()  # noqa: E731
    assert at(5)["flagged"] > at(10)["flagged"] > at(30)["flagged"]
    assert client.get(f"{API}/fields/F-047/grid/stats?threshold=90", headers=analyst).status_code == 422
    assert client.get(f"{API}/fields/F-047/grid/stats?size=3", headers=analyst).status_code == 422
    cmp = client.get(f"{API}/fields/F-047/grid/compare", headers=analyst).json()
    fr = [g["treatedFraction"] for g in cmp["grids"]]
    assert fr == sorted(fr) and fr[0] < fr[2]


def test_grid_cell_inspector(client, analyst):
    c = client.get(f"{API}/fields/F-047/grid/cell?x=60&y=44&size=2", headers=analyst).json()
    assert c["ref"] == "AE23" and c["inside"] and c["treated"] and c["weedClass"] == "GRASS" and c["severity"] == "HEAVY"
    assert client.get(f"{API}/fields/F-047/grid/cell?x=-50&y=0", headers=analyst).status_code == 404


def test_grid_for_unsurveyed_field_and_follow_up(client, analyst):
    assert client.get(f"{API}/fields/F-203/grid", headers=analyst).status_code == 404
    post = client.get(f"{API}/fields/F-047/grid/stats?survey=PLUS_14D", headers=analyst).json()
    assert post["flagged"] == 203  # the dashboard's own follow-up figure
    assert client.get(f"{API}/fields/F-047/grid/stats?survey=PLUS_28D", headers=analyst).status_code == 404


def test_zones_published_in_route_order(client, operator):
    zs = client.get(f"{API}/fields/F-047/zones", headers=operator).json()
    assert [z["letter"] for z in zs] == ["E", "A", "D", "B", "C"]
    assert [z["routeOrder"] for z in zs] == [1, 2, 3, 4, 5]
    assert all(z["state"] == "FLAGGED" and z["geometry"] for z in zs)
    a = next(z for z in zs if z["letter"] == "A")
    assert a["code"] == "Z-A" and a["label"] == "Zone A" and a["areaSqm"] == 988 and a["severity"] == "HEAVY"


def test_zone_state_route_treat_and_undo(client, phone):
    r = client.post(f"{API}/fields/F-047/zones/route-all", headers=phone)
    assert r.status_code == 200 and {z["state"] for z in r.json()} == {"ROUTED"}
    r = client.post(f"{API}/fields/F-047/zones/A/state", json={"state": "TREATED"}, headers=phone)
    assert r.status_code == 200 and r.json()["state"] == "TREATED" and r.json()["treatedAt"]
    f = client.get(f"{API}/fields/F-047", headers=phone).json()
    assert f["zonesDone"] == 1 and [s["key"] for s in f["loop"] if s["current"]] == ["treat"]
    r = client.post(f"{API}/fields/F-047/zones/Z-A/state", json={"state": "ROUTED"}, headers=phone)  # undo
    assert r.json()["state"] == "ROUTED" and r.json()["treatedAt"] is None
    r = client.post(f"{API}/fields/F-047/zones/A/state", json={"state": "RESURVEYED"}, headers=phone)
    assert r.status_code == 422
    assert client.post(f"{API}/fields/F-047/zones/Q/state", json={"state": "TREATED"}, headers=phone).status_code == 404


def test_route_resumes_from_operator_position(client, operator):
    gate = client.get(f"{API}/fields/F-047/route", headers=operator).json()
    assert gate["fromGate"] and [leg["letter"] for leg in gate["legs"]] == ["E", "A", "D", "B", "C"]
    assert gate["legs"][0]["distanceM"] == 31.4 and gate["legs"][0]["instruction"].startswith("Walk 31 m")
    here = client.get(f"{API}/fields/F-047/route?fromX=210&fromY=110", headers=operator).json()
    assert here["legs"][0]["letter"] == "C" and not here["fromGate"]
    assert client.get(f"{API}/fields/F-047/route?fromX=1", headers=operator).status_code == 422


def test_zone_preview_does_not_publish(client, analyst):
    pv = client.get(f"{API}/fields/F-047/zones/preview?threshold=30", headers=analyst).json()
    published = client.get(f"{API}/fields/F-047/zones", headers=analyst).json()
    assert sum(z["areaSqm"] for z in pv) < sum(z["areaSqm"] for z in published)
    assert all(z["thresholdPct"] == 10 for z in published)


def test_threshold_publish_rezones_and_keeps_observations(client, analyst, phone, operator):
    client.post(f"{API}/fields/F-047/zones/A/state", json={"state": "TREATED"}, headers=phone)
    assert client.put(f"{API}/settings/threshold", json={"thresholdPct": 20}, headers=operator).status_code == 403
    assert client.put(f"{API}/settings/threshold", json={"thresholdPct": 55}, headers=analyst).status_code == 422
    r = client.put(f"{API}/settings/threshold", json={"thresholdPct": 20}, headers=analyst)
    assert r.status_code == 200, r.text
    out = r.json()
    assert out["previousPct"] == 10 and out["thresholdPct"] == 20 and out["fieldsRezoned"] == 2
    zs = {z["letter"]: z for z in client.get(f"{API}/fields/F-047/zones", headers=analyst).json()}
    assert zs["A"]["state"] == "TREATED"  # the phone's observation survives a re-zoning
    assert zs["A"]["thresholdPct"] == 20 and zs["A"]["areaSqm"] < 988
    settings = client.get(f"{API}/settings", headers=operator).json()
    assert settings["thresholdPct"] == 20 and settings["thresholdPublishedBy"] == "Dr. S. Anjum"
    audit = client.get(f"{API}/audit", headers=analyst).json()["items"]
    assert audit[0]["action"] == "Threshold set" and "10% → 20%" in audit[0]["detail"]
