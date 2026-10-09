from __future__ import annotations

import io
import zipfile
from xml.etree import ElementTree as ET

import shapefile

from app.domain import geo
from tests.conftest import API


def test_sync_push_applies_dedupes_and_rejects_by_ownership(client, phone, analyst):
    changes = [
        {"clientSeq": 301, "entity": "treatment_zone", "op": "route", "payload": {"fieldId": "F-047"}},
        {"clientSeq": 302, "entity": "treatment_zone", "op": "state", "at": "2027-01-22T05:30:00Z",
         "payload": {"fieldId": "F-047", "zone": "E", "state": "TREATED"}},
        {"clientSeq": 303, "entity": "leaf_scan", "op": "create", "payload": {
            "clientId": "sync-scan-1", "fieldId": "F-047", "zoneLabel": "Zone C", "speciesLatin": "Convolvulus arvensis",
            "confidence": 0.55, "abstained": True, "runnerUp": [["Chenopodium album", 0.3]]}},
        {"clientSeq": 304, "entity": "abstention", "op": "annotate",
         "payload": {"scanClientId": "sync-scan-1", "species": "Convolvulus arvensis"}},
        {"clientSeq": 305, "entity": "treatment", "op": "create", "payload": {
            "clientId": "sync-treat-1", "fieldId": "F-047", "zoneLabels": ["Zone E"], "product": "Leader 75 WG",
            "doseRecorded": "13", "doseUnit": "g / acre", "applicationMode": "Knapsack sprayer",
            "growthStage": "Tillering (GS 21-25)", "areaAcres": 0.03}},
        {"clientSeq": 306, "entity": "settings", "op": "threshold", "payload": {"thresholdPct": 25}},
        {"clientSeq": 307, "entity": "field", "op": "update", "payload": {"fieldId": "F-047", "name": "Mine"}},
        {"clientSeq": 308, "entity": "leaf_scan", "op": "create", "payload": {"clientId": "bad", "fieldId": "F-047"}},
    ]
    r = client.post(f"{API}/sync/push", json={"changes": changes, "pendingAfter": 0}, headers=phone)
    assert r.status_code == 200, r.text
    out = r.json()
    status = {x["clientSeq"]: x["status"] for x in out["results"]}
    assert status == {301: "applied", 302: "applied", 303: "applied", 304: "applied", 305: "applied",
                      306: "rejected", 307: "rejected", 308: "rejected"}
    reasons = {x["clientSeq"]: x["reason"] for x in out["results"]}
    assert "definition" in reasons[306] and "definition" in reasons[307] and reasons[308].startswith("Invalid payload")
    # A retried push (lost acknowledgement) changes nothing.
    again = client.post(f"{API}/sync/push", json={"changes": changes}, headers=phone).json()
    assert {x["status"] for x in again["results"]} == {"duplicate"} and again["applied"] == 0
    zones = {z["letter"]: z["state"] for z in client.get(f"{API}/fields/F-047/zones", headers=analyst).json()}
    assert zones["E"] == "TREATED" and zones["A"] == "ROUTED"
    settings = client.get(f"{API}/settings", headers=analyst).json()
    assert settings["thresholdPct"] == 10  # the phone cannot move a definition
    ledger = client.get(f"{API}/sync/changes?deviceId=D-01&status=rejected", headers=analyst).json()
    assert ledger["total"] == 3 and all(c["owner"] == "phone" for c in ledger["items"] if c["entity"] != "settings")
    devices = {d["id"]: d for d in client.get(f"{API}/devices", headers=analyst).json()}
    assert devices["D-01"]["pendingChanges"] == 0 and devices["D-01"]["lastSyncAt"].startswith("2027-01-22")


def test_sync_requires_the_operators_own_device(client, analyst, operator):
    assert client.post(f"{API}/sync/push", json={"changes": []}, headers=operator).status_code == 403
    assert client.post(f"{API}/sync/push", json={"changes": []}, headers={**analyst, "X-Device-ID": "D-01"}).status_code == 403


def test_sync_pull_full_and_incremental(client, phone, analyst):
    full = client.get(f"{API}/sync/pull", headers=phone).json()
    assert full["full"] is True and len(full["fields"]) == 3 and len(full["zones"]) == 7
    assert len(full["products"]) == 11 and len(full["species"]) == 5 and full["settings"]["thresholdPct"] == 10
    assert len(full["scans"]) == 6 and len(full["treatments"]) == 4
    cursor = full["cursor"]
    nothing = client.get(f"{API}/sync/pull", params={"since": cursor}, headers=phone).json()
    assert nothing["fields"] == [] and nothing["full"] is False
    client.post(f"{API}/scans/SC-039/resolve", json={"species": "Convolvulus arvensis"}, headers=analyst)
    client.put(f"{API}/settings/threshold", json={"thresholdPct": 12}, headers=analyst)
    later = client.get(f"{API}/sync/pull", params={"since": cursor}, headers=phone).json()
    assert [s["id"] for s in later["scans"]] == ["SC-039"] and later["scans"][0]["status"] == "RESOLVED"
    assert later["settings"]["thresholdPct"] == 12 and {z["thresholdPct"] for z in later["zones"]} == {12}
    trainee_view = client.get(f"{API}/sync/pull", headers=_trainee(client)).json()
    assert [f["id"] for f in trainee_view["fields"]] == ["F-203"] and trainee_view["scans"] == []


def _trainee(client):
    from tests.conftest import TRAINEE, auth, login

    return auth(login(client, TRAINEE))


def test_heartbeat_and_revoke(client, phone, admin, analyst):
    r = client.post(f"{API}/devices/D-01/heartbeat", json={"battery": 41, "pendingChanges": 5}, headers=phone)
    assert r.status_code == 200 and r.json()["battery"] == 41 and r.json()["online"] is True
    assert client.post(f"{API}/devices/D-01/revoke", headers=analyst).status_code == 403
    assert client.post(f"{API}/devices/D-01/revoke", headers=admin).json()["revoked"] is True
    assert client.post(f"{API}/sync/push", json={"changes": []}, headers=phone).status_code == 403


def test_export_geojson(client, analyst):
    r = client.post(f"{API}/exports", json={"fieldId": "F-047", "format": "GeoJSON", "gridSize": 2}, headers=analyst)
    assert r.status_code == 201, r.text
    job = r.json()
    assert job["id"] == "X-04" and job["filename"] == "chak-47_prescription_2m_t10.geojson" and job["cells"] == 688 and job["zones"] == 5
    d = client.get(job["downloadUrl"], headers=analyst)
    assert d.headers["content-type"].startswith("application/geo+json") and "attachment" in d.headers["content-disposition"]
    doc = d.json()
    kinds = [f["properties"]["kind"] for f in doc["features"]]
    assert kinds.count("boundary") == 1 and kinds.count("zone") == 5 and kinds.count("spray") == 688
    ring = doc["features"][0]["geometry"]["coordinates"][0]
    assert geo.signed_area(ring[:-1]) > 0  # RFC 7946: exterior rings counter-clockwise
    keys = set(doc["properties"]) | {k for f in doc["features"] for k in f["properties"]}
    assert not any(part in ("rate", "dose") for k in keys for part in k.split("_"))  # geometry only, never a rate


def test_export_shapefile_is_readable(client, analyst):
    job = client.post(f"{API}/exports", json={"fieldId": "F-112", "format": "Shapefile", "include": {"abstained": True}},
                      headers=analyst).json()
    body = client.get(job["downloadUrl"], headers=analyst).content
    zf = zipfile.ZipFile(io.BytesIO(body))
    base = job["filename"][:-4]
    names = set(zf.namelist())
    assert {f"{base}_zones.{e}" for e in ("shp", "shx", "dbf", "prj")} <= names
    rdr = shapefile.Reader(shp=io.BytesIO(zf.read(f"{base}_zones.shp")), shx=io.BytesIO(zf.read(f"{base}_zones.shx")),
                           dbf=io.BytesIO(zf.read(f"{base}_zones.dbf")))
    assert [r["LABEL"] for r in rdr.records()] == ["Zone A", "Zone B"]
    cells = shapefile.Reader(shp=io.BytesIO(zf.read(f"{base}_cells.shp")), dbf=io.BytesIO(zf.read(f"{base}_cells.dbf")))
    assert {r["KIND"] for r in cells.records()} == {"spray", "abstained"}


def test_export_taskdata_and_preview(client, analyst, operator):
    pv = client.post(f"{API}/exports/preview", json={"fieldId": "F-047", "format": "TASKDATA"}, headers=analyst).json()
    assert pv["preview"].startswith('<?xml version="1.0" encoding="UTF-8"?>') and pv["zones"] == 5 and pv["cells"] == 0
    job = client.post(f"{API}/exports", json={"fieldId": "F-047", "format": "TASKDATA", "thresholdPct": 20}, headers=analyst).json()
    root = ET.fromstring(client.get(job["downloadUrl"], headers=analyst).content)
    assert root.tag == "ISO11783_TaskData"
    tzn = root.findall("./TSK/TZN")
    assert len(tzn) == job["zones"] and all(t.find("PLN").get("A") == "2" for t in tzn)
    assert root.find("./PFD/PLN").get("A") == "1" and not root.findall(".//PDV")
    hist = client.get(f"{API}/exports", headers=analyst).json()
    assert [j["id"] for j in hist][:1] == [job["id"]] and {"X-02", "X-03"} <= {j["id"] for j in hist}
    seeded = client.get(f"{API}/exports/X-03/download", headers=analyst)  # rebuilt from its recorded settings
    assert seeded.status_code == 200 and seeded.json()["type"] == "FeatureCollection"
    assert client.post(f"{API}/exports", json={"fieldId": "F-203", "format": "GeoJSON"}, headers=analyst).status_code == 404


def test_overview_needs_attention(client, analyst):
    ov = client.get(f"{API}/overview", headers=analyst).json()
    st = ov["stats"]
    assert st["fields"] == 3 and round(st["areaAcres"], 1) == 15.7 and st["zonesOpen"] == 7
    assert st["scansAwaitingReview"] == 2 and st["seasonDay"] == 72 and st["worstControl"]["letter"] == "C"
    kinds = [i["kind"] for i in ov["needsAttention"]]
    assert kinds[0] == "control_failure" and ov["needsAttention"][0]["title"] == "Zone C barely responded on Chak 47"
    assert {"resistance", "zones_open", "review_scans", "review_cells", "flight_processing", "device_pending", "verify"} <= set(kinds)
    assert ov["recentActivity"] and ov["recentAudit"][0]["id"] == "A-9"


def test_activity_season_audit_conditions_settings(client, analyst, operator):
    act = client.get(f"{API}/activity?fieldId=F-047&limit=5", headers=operator).json()
    assert len(act["items"]) == 5 and act["nextBefore"]
    older = client.get(f"{API}/activity", params={"fieldId": "F-047", "before": act["nextBefore"]}, headers=operator).json()
    assert all(o["at"] < act["nextBefore"] for o in older["items"])
    scans_only = client.get(f"{API}/activity?kind=SCAN", headers=operator).json()["items"]
    assert {i["kind"] for i in scans_only} == {"SCAN"} and scans_only[0]["title"] == "Triticum aestivum"
    season = client.get(f"{API}/season", headers=operator).json()
    assert season["startsOn"] == "2026-11-12" and len(season["fields"]) == 3
    assert client.get(f"{API}/audit", headers=operator).status_code == 403
    c = client.get(f"{API}/conditions?fieldId=F-047", headers=operator).json()
    assert c["windFrom"] == "NW" and c["sprayWindow"] == "good"
    r = client.patch(f"{API}/settings", json={"defaultGridM": 5, "orgName": "Pindi Bhattian station"}, headers=analyst)
    assert r.json()["defaultGridM"] == 5
    assert client.patch(f"{API}/settings", json={"defaultGridM": 5}, headers=operator).status_code == 403
    assert client.get(f"{API}/fields/F-047/grid/stats", headers=operator).json()["cellMeters"] == 5


def test_health(client):
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/ready").json()["status"] == "ready"
    r = client.get(f"{API}/nope")
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found" and r.headers["x-request-id"]
