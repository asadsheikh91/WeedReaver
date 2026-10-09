from __future__ import annotations

import io
import zipfile

from app.pipeline.runner import run_pending
from tests.conftest import API

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64 + b"\xff\xd9"


def test_seeded_processing_flight_completes_from_41_percent(client, analyst):
    s = client.get(f"{API}/surveys/S-06", headers=analyst).json()
    assert s["status"] == "PROCESSING" and s["progress"] == 0.41 and s["job"]["status"] == "QUEUED"
    v = client.get(f"{API}/fields/F-112/verification", headers=analyst).json()
    assert v["ready"] is False and "orthomosaic" in v["reason"]
    assert run_pending() == 1
    s = client.get(f"{API}/surveys/S-06", headers=analyst).json()
    assert s["status"] == "READY" and s["progress"] == 1 and s["job"]["status"] == "SUCCEEDED" and s["hasSurface"]
    v = client.get(f"{API}/fields/F-112/verification", headers=analyst).json()
    assert v["ready"] is True and {z["letter"] for z in v["zones"]} == {"A", "B"}


def test_upload_simulated_pre_flight_publishes_zones(client, analyst):
    r = client.post(f"{API}/surveys/uploads", json={"fieldId": "F-203", "role": "PRE"}, headers=analyst)
    assert r.status_code == 201 and r.json()["id"] == "S-05"  # reuses the scheduled flight
    assert client.post(f"{API}/surveys/S-05/process", json={}, headers=analyst).status_code == 422  # no images yet
    r = client.post(f"{API}/surveys/S-05/process", json={"simulateImages": 287}, headers=analyst)
    assert r.status_code == 202 and r.json()["status"] == "QUEUED"
    assert client.post(f"{API}/surveys/S-05/process", json={"simulateImages": 1}, headers=analyst).status_code == 409
    run_pending()
    s = client.get(f"{API}/surveys/S-05", headers=analyst).json()
    assert s["status"] == "READY" and s["gsdCm"] == 0.42 and s["images"] == 287
    f = client.get(f"{API}/fields/F-203", headers=analyst).json()
    assert f["surveyed"] is True
    # North plot's only patch peaks at 16%: below the 30% needed to make a zone.
    assert f["zonesTotal"] == 0 and f["upNext"] == "No treatment indicated"


def test_new_field_full_loop_with_real_image_upload(client, analyst, operator):
    body = {"clientId": "loop-field-1", "name": "Loop field", "captureMethod": "Drawn",
            "boundary": [{"x": 0, "y": 0}, {"x": 140, "y": 0}, {"x": 140, "y": 100}, {"x": 0, "y": 100}], "lat": 31.9, "lon": 73.27}
    fid = client.post(f"{API}/fields", json=body, headers=operator).json()["id"]
    sid = client.post(f"{API}/surveys/uploads", json={"fieldId": fid, "role": "PRE"}, headers=analyst).json()["id"]
    zbuf = io.BytesIO()
    with zipfile.ZipFile(zbuf, "w") as zf:
        zf.writestr("DJI_0002.JPG", JPEG)
        zf.writestr("notes.txt", "ignored")
    files = [("files", ("DJI_0001.JPG", JPEG, "image/jpeg")), ("files", ("evil.exe", b"MZ\x90\x00", "application/octet-stream")),
             ("files", ("batch.zip", zbuf.getvalue(), "application/zip"))]
    up = client.post(f"{API}/surveys/{sid}/images", files=files, headers=analyst).json()
    assert up["received"] == 2 and up["images"] == 2 and len(up["rejected"]) == 1
    assert client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst).status_code == 202
    run_pending()
    zones = client.get(f"{API}/fields/{fid}/zones", headers=analyst).json()
    assert client.get(f"{API}/surveys/{sid}", headers=analyst).json()["status"] == "READY"
    assert len(zones) >= 1
    # A follow-up flight: zones the phone treated respond, the rest barely move.
    first = zones[0]["letter"]
    client.post(f"{API}/fields/{fid}/zones/{first}/state", json={"state": "TREATED"}, headers=operator)
    fsid = client.post(f"{API}/surveys/uploads", json={"fieldId": fid, "role": "PLUS_14D"}, headers=analyst).json()["id"]
    client.post(f"{API}/surveys/{fsid}/process", json={"simulateImages": 300}, headers=analyst)
    run_pending()
    v = client.get(f"{API}/fields/{fid}/verification", headers=analyst).json()
    assert v["ready"] is True
    eff = {z["letter"]: z["efficacyPct"] for z in v["zones"]}
    assert eff[first] >= 50
    assert all(e <= 30 for letter, e in eff.items() if letter != first)


def test_schedule_patch_cancel(client, analyst, operator):
    assert client.post(f"{API}/surveys", json={"fieldId": "F-112", "role": "PLUS_28D"}, headers=operator).status_code == 403
    r = client.post(f"{API}/surveys", json={"fieldId": "F-112", "role": "PLUS_28D"}, headers=analyst)
    assert r.status_code == 201
    s = r.json()
    assert s["status"] == "SCHEDULED" and s["flownAt"].startswith("2027-02-05")  # 28 days after the pre flight
    assert client.post(f"{API}/surveys", json={"fieldId": "F-112", "role": "PLUS_28D"}, headers=analyst).status_code == 409
    r = client.patch(f"{API}/surveys/{s['id']}", json={"altitudeM": 20}, headers=analyst)
    assert r.json()["altitudeM"] == 20
    assert client.delete(f"{API}/surveys/{s['id']}", headers=analyst).status_code == 200
    assert client.delete(f"{API}/surveys/S-01", headers=analyst).status_code == 409


def test_failing_engine_marks_flight_failed(client, analyst, monkeypatch):
    from app.pipeline import runner
    from app.pipeline.base import PipelineError

    class Broken:
        name = "broken"
        requires_images = False

        def build(self, inputs, progress):
            raise PipelineError("Not enough overlap between images")

    seg = runner.engines()[1]
    monkeypatch.setattr(runner, "engines", lambda: (Broken(), seg))
    client.post(f"{API}/surveys/uploads", json={"fieldId": "F-203", "role": "PRE"}, headers=analyst)
    client.post(f"{API}/surveys/S-05/process", json={"simulateImages": 10}, headers=analyst)
    run_pending()
    s = client.get(f"{API}/surveys/S-05", headers=analyst).json()
    assert s["status"] == "FAILED" and s["error"] == "Not enough overlap between images"
    ov = client.get(f"{API}/overview", headers=analyst).json()
    assert any(i["kind"] == "flight_failed" for i in ov["needsAttention"])
    monkeypatch.setattr(runner, "engines", lambda: (runner.load_engine("app.pipeline.orthomosaic:SimulatedOrthomosaicEngine"), seg))
    assert client.post(f"{API}/surveys/S-05/retry", headers=analyst).status_code == 202
    run_pending()
    assert client.get(f"{API}/surveys/S-05", headers=analyst).json()["status"] == "READY"


def test_list_surveys_filters(client, analyst):
    all_ = client.get(f"{API}/surveys", headers=analyst).json()
    assert [s["id"] for s in all_][:2] == ["S-01", "S-04"]
    ready = client.get(f"{API}/surveys?status=READY&fieldId=F-047", headers=analyst).json()
    assert [s["id"] for s in ready] == ["S-01", "S-02"]
