from __future__ import annotations

from tests.conftest import API

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 40


def scan_body(**kw) -> dict:
    body = {"clientId": "scan-client-0001", "fieldId": "F-047", "zoneLabel": "Zone B", "speciesLatin": "Avena ludoviciana",
            "confidence": 0.58, "abstained": True, "runnerUp": [["Phalaris minor", 0.31]], "inferenceMs": 150,
            "modelVersion": "wr-leaf-yolo11n-int8 v0.3.2", "gnssAccuracyM": 3.8}
    body.update(kw)
    return body


def test_scan_create_idempotent_and_positioned(client, phone):
    r = client.post(f"{API}/scans", json=scan_body(), headers=phone)
    assert r.status_code == 201, r.text
    s = r.json()
    assert s["id"] == "SC-041" and s["status"] == "NEEDS_LABEL" and s["deviceId"] == "D-01" and s["weedClass"] == "GRASS"
    assert s["speciesLocal"] == "Jangli jai" and s["lat"] and s["lon"]  # placed at Zone B without a GNSS fix
    again = client.post(f"{API}/scans", json=scan_body(), headers=phone)
    assert again.status_code == 200 and again.json()["id"] == "SC-041"
    unknown = client.post(f"{API}/scans", json=scan_body(clientId="scan-client-0002", speciesLatin="Rumex dentatus"), headers=phone)
    assert unknown.status_code == 422


def test_review_loop(client, phone, analyst, operator):
    waiting = client.get(f"{API}/scans?status=NEEDS_LABEL", headers=analyst).json()
    assert waiting["total"] == 2 and {s["id"] for s in waiting["items"]} == {"SC-036", "SC-039"}
    r = client.post(f"{API}/scans/SC-036/annotation", json={"species": "Phalaris minor", "note": "ligule visible"}, headers=phone)
    assert r.status_code == 200 and r.json()["status"] == "ANNOTATED" and r.json()["annotatedBy"] == "Asad Mehmood"
    assert client.post(f"{API}/scans/SC-035/annotation", json={"species": "x"}, headers=phone).status_code == 409
    assert client.post(f"{API}/scans/SC-036/resolve", json={"species": "Phalaris minor"}, headers=operator).status_code == 403
    r = client.post(f"{API}/scans/SC-036/resolve", json={"species": "Phalaris minor"}, headers=analyst)
    assert r.json()["status"] == "RESOLVED" and r.json()["resolvedBy"] == "Dr. S. Anjum" and r.json()["displayLabel"] == "Phalaris minor"
    summary = client.get(f"{API}/review/summary", headers=analyst).json()
    assert summary["scansWaiting"] == 1 and summary["scansResolved"] == 1 and summary["cellsAbstained"] > 0
    assert client.post(f"{API}/scans/SC-036/reopen", headers=analyst).json()["status"] == "ANNOTATED"


def test_scan_photo(client, phone, analyst):
    sid = client.post(f"{API}/scans", json=scan_body(), headers=phone).json()["id"]
    bad = client.put(f"{API}/scans/{sid}/photo", files={"file": ("x.jpg", b"not an image", "image/jpeg")}, headers=phone)
    assert bad.status_code == 422
    r = client.put(f"{API}/scans/{sid}/photo", files={"file": ("leaf.png", PNG, "image/png")}, headers=phone)
    assert r.status_code == 200 and r.json()["hasPhoto"]
    photo = client.get(f"{API}/scans/{sid}/photo", headers=analyst)
    assert photo.status_code == 200 and photo.content == PNG and photo.headers["content-type"] == "image/png"


def test_review_cells(client, analyst):
    cells = client.get(f"{API}/review/cells", headers=analyst).json()
    by = {c["fieldId"]: c for c in cells}
    assert by["F-047"]["count"] == 43 and by["F-047"]["message"] == "43 cells in Chak 47 need a look"
    assert by["F-047"]["cells"][0]["ref"] and by["F-047"]["cells"][0]["lat"]


def treatment_body(**kw) -> dict:
    body = {"clientId": "treat-client-0001", "fieldId": "F-047", "zoneLabels": ["Zone A", "e"], "product": "Topik 15 WP",
            "doseRecorded": "100", "doseUnit": "g / acre", "applicationMode": "Knapsack sprayer",
            "growthStage": "Tillering (GS 21-25)", "areaAcres": 0.28, "waterLitres": "100"}
    body.update(kw)
    return body


def test_treatment_records_dose_verbatim_and_warns_on_rotation(client, phone, analyst):
    r = client.post(f"{API}/treatments", json=treatment_body(rotationOverride=True), headers=phone)
    assert r.status_code == 201, r.text
    out = r.json()
    t = out["treatment"]
    assert t["id"] == "T-0118" and t["doseRecorded"] == "100" and t["hracGroup"] == "G1" and t["zoneLabels"] == ["Zone A", "Zone E"]
    assert t["activeIngredient"] == "Clodinafop-propargyl" and t["operator"] == "Asad Mehmood" and t["rotationOverride"] is True
    assert out["rotationWarning"].startswith("Fourth application in a row of Group 1")
    assert client.post(f"{API}/treatments", json=treatment_body(), headers=phone).status_code == 200
    bad = [
        treatment_body(clientId="treat-2", doseUnit="litres"),
        treatment_body(clientId="treat-3", doseRecorded="a lot"),
        treatment_body(clientId="treat-4", zoneLabels=["Zone Z"]),
        treatment_body(clientId="treat-5", product="Mystery Mix"),
    ]
    for b in bad:
        assert client.post(f"{API}/treatments", json=b, headers=phone).status_code == 422, b
    listing = client.get(f"{API}/treatments?fieldId=F-047&hrac=G1", headers=analyst).json()
    assert listing["total"] == 4 and listing["items"][0]["id"] == "T-0118"
    assert client.get(f"{API}/treatments?season=2025-26", headers=analyst).json()["total"] == 2


def test_rotation_story(client, analyst, operator):
    r = client.get(f"{API}/fields/F-047/rotation", headers=analyst).json()
    assert [p["controlPct"] for p in r["trend"]] == [81, 64, 47]
    assert r["streak"] == 3 and r["risk"] == "High" and r["latestGroup"] == "G1"
    assert r["warning"].startswith("Third season in a row on Group 1 · ACCase inhibitor") and "81% → 64% → 47%" in r["warning"]
    assert r["alternatives"] and all(a["hrac"] != "G1" for a in r["alternatives"])
    chk = client.post(f"{API}/rotation/check", json={"fieldId": "F-047", "product": "Axial 50 EC"}, headers=operator).json()
    assert chk["clash"] is True and chk["streak"] == 3 and chk["alternatives"]
    ok = client.post(f"{API}/rotation/check", json={"fieldId": "F-047", "productId": "P-05"}, headers=operator).json()
    assert ok["clash"] is False and ok["hrac"] == "G2"
    canal = client.get(f"{API}/fields/F-112/rotation", headers=analyst).json()
    assert canal["risk"] == "Low" and canal["streak"] == 1


def test_verification_preview_and_save(client, phone, analyst):
    v = client.get(f"{API}/fields/F-047/verification", headers=analyst).json()
    assert v["ready"] is True and v["worst"]["letter"] == "C" and v["worst"]["efficacyPct"] < 40
    assert v["fieldDeltaPct"] == round((1 - 203 / 688) * 100) and 0.9 < v["chemicalSavedFraction"] < 1
    assert "not a yield gain" in v["caveat"]
    late = client.get(f"{API}/fields/F-047/verification?role=PLUS_28D", headers=analyst).json()
    assert late["ready"] is False and late["followStatus"] == "SCHEDULED"
    r = client.post(f"{API}/fields/F-047/verification", json={"clientId": "verify-0001"}, headers=phone)
    assert r.status_code == 201, r.text
    assert r.json()["worstEfficacyPct"] == v["worst"]["efficacyPct"]
    zones = client.get(f"{API}/fields/F-047/zones", headers=analyst).json()
    assert {z["state"] for z in zones} == {"RESURVEYED"} and all(z["efficacyPct"] is not None for z in zones)
    assert client.post(f"{API}/fields/F-047/verification", json={"clientId": "verify-0001"}, headers=phone).status_code == 200
    rot = client.get(f"{API}/fields/F-047/rotation", headers=analyst).json()
    assert rot["seasons"][-1]["season"] == "2025-26"  # no 2026-27 treatment recorded yet, so no new rotation row
    assert client.post(f"{API}/fields/F-203/verification", json={}, headers=phone).status_code == 409


def test_quadrats_products_species_reference(client, phone, analyst):
    q = client.get(f"{API}/quadrats?fieldId=F-047", headers=analyst).json()
    assert [x["id"] for x in q] == ["Q-03", "Q-02", "Q-01"] and q[2]["total"] == 48
    r = client.post(f"{API}/quadrats", json={"clientId": "quad-1", "frameId": "ARUCO-21", "fieldId": "F-047", "zoneLabel": "Zone D",
                                             "speciesCounts": [["Chenopodium album", 9]]}, headers=phone)
    assert r.status_code == 201 and r.json()["id"] == "Q-04"
    products = client.get(f"{API}/products?target=BROADLEAF", headers=analyst).json()
    assert {p["trade"] for p in products} == {"Sencor 70 WP", "Buctril Super 60 EC", "Round-up 41 SL"}
    assert client.post(f"{API}/products", json={"trade": "Topik 15 WP", "active": "x", "hrac": "G1", "target": "GRASS",
                                                 "crop": "Wheat", "formulation": "x"}, headers=analyst).status_code == 409
    assert client.post(f"{API}/products", json={"trade": "New", "active": "x", "hrac": "G1", "target": "GRASS", "crop": "Wheat",
                                                 "formulation": "x"}, headers=phone).status_code == 403
    assert len(client.get(f"{API}/species", headers=phone).json()) == 5
    ref = client.get(f"{API}/reference", headers=phone).json()
    assert ref["doseUnits"] == ["g / acre", "mL / acre", "L / acre", "kg / acre"] and len(ref["hracGroups"]) == 7
