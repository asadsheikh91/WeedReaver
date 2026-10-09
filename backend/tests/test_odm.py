"""The OpenDroneMap engine against a scripted stand-in for a NodeODM server.

Nothing here needs Docker: the fake node speaks pyodm's interface (info, create_task, get_task,
task.info/restart/output/download_assets/remove) and writes a real GeoTIFF header on download.
"""

from __future__ import annotations

import struct
from pathlib import Path
from types import SimpleNamespace

import pytest
from pyodm.exceptions import NodeConnectionError
from pyodm.types import TaskStatus

from app.core.config import get_settings
from app.domain import geo
from app.pipeline import geotiff, runner
from app.pipeline.base import FlightInputs
from app.pipeline.orthomosaic import NodeODMOrthomosaicEngine, boundary_geojson, odm_options
from app.pipeline.runner import run_pending
from tests.conftest import API

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64 + b"\xff\xd9"


# ----------------------------------------------------------------------------- a GeoTIFF on disk


def write_geotiff(path: Path, *, width=4000, height=3000, pixel_m=0.0042, origin=(310_000.0, 3_530_000.0),
                  epsg=32643, big=False, byteorder="<") -> None:
    """A header-only GeoTIFF: what ODM's orthophoto looks like to the reader (UTM 43N by default)."""
    bo = byteorder
    scale = struct.pack(bo + "3d", pixel_m, pixel_m, 0.0)
    tie = struct.pack(bo + "6d", 0.0, 0.0, 0.0, origin[0], origin[1], 0.0)
    keys = struct.pack(bo + "12H", 1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, epsg)
    blobs = [scale, tie, keys]
    # (tag, type, count, inline value or blob index)
    entries = [(256, 4, 1, width), (257, 4, 1, height), (259, 3, 1, 8), (277, 3, 1, 4),
               (33550, 12, 3, ("blob", 0)), (33922, 12, 6, ("blob", 1)), (34735, 3, 12, ("blob", 2))]
    head = 16 if big else 8
    entry_size, count_size, next_size = (20, 8, 8) if big else (12, 2, 4)
    ifd_size = count_size + entry_size * len(entries) + next_size
    data_at = head + ifd_size
    offsets, cursor = [], data_at
    for b in blobs:
        offsets.append(cursor)
        cursor += len(b)
    out = bytearray(b"II" if bo == "<" else b"MM")
    out += struct.pack(bo + "H", 43 if big else 42)
    out += struct.pack(bo + "HHQ", 8, 0, head) if big else struct.pack(bo + "I", head)
    out += struct.pack(bo + ("Q" if big else "H"), len(entries))
    for tag, typ, count, value in entries:
        if isinstance(value, tuple):
            field = struct.pack(bo + ("Q" if big else "I"), offsets[value[1]])
        elif typ == 3:
            field = struct.pack(bo + "H", value).ljust(8 if big else 4, b"\0")
        else:
            field = struct.pack(bo + "I", value).ljust(8 if big else 4, b"\0")
        out += struct.pack(bo + ("HHQ" if big else "HHI"), tag, typ, count) + field
    out += struct.pack(bo + ("Q" if big else "I"), 0)
    for b in blobs:
        out += b
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))


@pytest.mark.parametrize("big,bo", [(False, "<"), (True, "<"), (False, ">"), (True, ">")])
def test_geotiff_header_reader(tmp_path, big, bo):
    p = tmp_path / "o.tif"
    write_geotiff(p, big=big, byteorder=bo)
    t = geotiff.describe(p)
    assert (t.width, t.height, t.bands) == (4000, 3000, 4)
    assert t.epsg == 32643 and not t.geographic and t.compression == "DEFLATE"
    assert t.gsd_cm() == pytest.approx(0.42)
    assert (t.origin_x, t.origin_y) == (310_000.0, 3_530_000.0)


def test_geotiff_reader_rejects_other_files(tmp_path):
    p = tmp_path / "x.tif"
    p.write_bytes(JPEG)
    with pytest.raises(ValueError):
        geotiff.describe(p)


# ----------------------------------------------------------------------------- options and boundary


def _inputs(tmp_path: Path, images: int = 3) -> FlightInputs:
    paths = []
    for k in range(images):
        p = tmp_path / "images" / f"DJI_{k:04d}.JPG"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(JPEG)
        paths.append(p)
    return FlightInputs(
        survey_id="S-99", field_id="F-047", role="PRE", image_paths=paths, image_count=images, altitude_m=15,
        sensor="DJI Mavic 3M", boundary=[(22, 0), (243, 0), (245, 142), (1, 143), (0, 24), (22, 24)],
        anchor_lat=31.8942, anchor_lon=73.2711, workdir=tmp_path / "work",
    )


def test_boundary_covers_the_field_with_a_margin(tmp_path):
    inp = _inputs(tmp_path)
    gj = boundary_geojson(inp, 15.0)
    ring = gj["features"][0]["geometry"]["coordinates"][0]
    assert ring[0] == ring[-1] and len(ring) >= 5
    local = [geo.from_latlon(inp.anchor_lat, inp.anchor_lon, lat, lon) for lon, lat in ring[:-1]]
    for x, y in inp.boundary:
        assert geo.contains(local, x, y)
    minx, miny, maxx, maxy = geo.bounds(local)
    assert minx == pytest.approx(-15, abs=0.2) and miny == pytest.approx(-15, abs=0.2)
    assert maxx == pytest.approx(260, abs=0.5) and maxy == pytest.approx(158, abs=0.5)


def test_options_tuned_for_flat_nadir_flights_and_overridable(tmp_path, monkeypatch):
    inp = _inputs(tmp_path)
    o = odm_options(inp)
    assert o["fast-orthophoto"] is True and o["sfm-algorithm"] == "planar" and o["cog"] is True
    assert o["orthophoto-resolution"] == 0.42  # the flight's own GSD at 15 m
    assert o["auto-boundary"] is True and "boundary" not in o  # NodeODM cannot carry GeoJSON (quotes stripped)
    monkeypatch.setattr(get_settings(), "odm_orthophoto_cm", 1.0)
    monkeypatch.setattr(get_settings(), "odm_options", {"feature-quality": "medium", "fast-orthophoto": False})
    o = odm_options(inp)
    assert o["orthophoto-resolution"] == 1.0 and o["feature-quality"] == "medium" and o["fast-orthophoto"] is False


# ----------------------------------------------------------------------------- a scripted NodeODM


class FakeTask:
    def __init__(self, node: "FakeNode", uuid: str, script: list):
        self.node, self.uuid, self.script = node, uuid, list(script)
        self.restarts: list[dict] = []
        self.removed = False
        self.output_lines = ["[INFO]    Running ODM", "[INFO]    Finished"]

    def info(self):
        step = self.script.pop(0) if len(self.script) > 1 else self.script[0]
        if isinstance(step, Exception):
            raise step
        status, progress = step
        return SimpleNamespace(status=status, progress=progress, processing_time=125_000, last_error="")

    def restart(self, options=None):
        self.restarts.append(options or {})
        self.script = list(self.node.after_restart)
        return True

    def output(self, line=0):
        return self.output_lines

    def download_assets(self, destination, progress_callback=None, **_):
        write_geotiff(Path(destination) / "odm_orthophoto" / "odm_orthophoto.tif")
        if progress_callback:
            progress_callback(100.0)
        self.node.downloads += 1
        return destination

    def cancel(self):
        return True

    def remove(self):
        self.removed = True
        return True


class FakeNode:
    def __init__(self, script, after_restart=None, info_error: Exception | None = None):
        self.script, self.after_restart = script, after_restart or [(TaskStatus.COMPLETED, 100)]
        self.info_error = info_error
        self.tasks: dict[str, FakeTask] = {}
        self.created: list[dict] = []
        self.downloads = 0
        self.token = ""

    def info(self):
        if self.info_error:
            raise self.info_error
        return SimpleNamespace(engine_version="3.5.6", version="2.2.4", max_images=None)

    def create_task(self, files, options, name=None, progress_callback=None, task_uuid=None, outputs=None, **kw):
        self.created.append({"files": files, "options": options, "outputs": outputs, "uuid": task_uuid, **kw})
        if progress_callback:
            progress_callback(50.0)
            progress_callback(100.0)
        t = FakeTask(self, task_uuid, self.script)
        self.tasks[task_uuid] = t
        return t

    def get_task(self, uuid):
        return self.tasks.get(uuid) or FakeTask(self, uuid, [NodeConnectionError("gone")])


@pytest.fixture
def odm(monkeypatch):
    """Route the pipeline to the ODM engine and a fake node; returns a setter for the node."""
    import pyodm

    seg = runner.engines()[1]
    monkeypatch.setattr(runner, "engines", lambda: (NodeODMOrthomosaicEngine(), seg))
    monkeypatch.setattr(get_settings(), "odm_poll_seconds", 0.0)
    holder: dict = {}
    monkeypatch.setattr(pyodm.Node, "from_url", staticmethod(lambda url, timeout=30: holder["node"]))

    def use(node: FakeNode) -> FakeNode:
        holder["node"] = node
        return node

    return use


def _flight_with_images(client, analyst, operator, name="ODM field"):
    body = {"clientId": f"odm-{name}", "name": name, "captureMethod": "Drawn",
            "boundary": [{"x": 0, "y": 0}, {"x": 140, "y": 0}, {"x": 140, "y": 100}, {"x": 0, "y": 100}], "lat": 31.9, "lon": 73.27}
    fid = client.post(f"{API}/fields", json=body, headers=operator).json()["id"]
    sid = client.post(f"{API}/surveys/uploads", json={"fieldId": fid, "role": "PRE"}, headers=analyst).json()["id"]
    files = [("files", (f"DJI_{k:04d}.JPG", JPEG, "image/jpeg")) for k in range(3)]
    assert client.post(f"{API}/surveys/{sid}/images", files=files, headers=analyst).json()["received"] == 3
    assert client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst).status_code == 202
    return fid, sid


def test_flight_is_stitched_by_odm_end_to_end(client, analyst, operator, odm):
    node = odm(FakeNode([(TaskStatus.QUEUED, 0), (TaskStatus.RUNNING, 40), (TaskStatus.RUNNING, 90), (TaskStatus.COMPLETED, 100)]))
    _, sid = _flight_with_images(client, analyst, operator)
    run_pending()
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert s["status"] == "READY", s["error"]
    assert s["hasOrthomosaic"] and s["gsdCm"] == 0.42
    assert s["pipeline"] == "OpenDroneMap 3.5.6 · NodeODM 2.2.4"
    assert len(node.created) == 1
    sent = node.created[0]
    assert len(sent["files"]) == 3 and sent["options"]["sfm-algorithm"] == "planar"
    assert sent["outputs"] == ["odm_orthophoto/odm_orthophoto.tif", "odm_report/report.pdf"]
    assert sent["skip_post_processing"] is True
    assert node.tasks[sent["uuid"]].removed  # the node's copy of the images is freed
    r = client.get(f"{API}/surveys/{sid}/orthomosaic", headers=analyst)
    assert r.status_code == 200 and r.content[:2] == b"II"


def test_failed_fast_run_restarts_once_without_reupload(client, analyst, operator, odm):
    node = odm(FakeNode([(TaskStatus.RUNNING, 30), (TaskStatus.FAILED, 30)],
                        after_restart=[(TaskStatus.RUNNING, 10), (TaskStatus.COMPLETED, 100)]))
    _, sid = _flight_with_images(client, analyst, operator)
    run_pending()
    assert client.get(f"{API}/surveys/{sid}", headers=analyst).json()["status"] == "READY"
    task = next(iter(node.tasks.values()))
    assert len(node.created) == 1 and len(task.restarts) == 1
    assert task.restarts[0]["sfm-algorithm"] == "incremental" and task.restarts[0]["auto-boundary"] is True


def test_odm_failure_reads_as_a_cause(client, analyst, operator, odm):
    node = odm(FakeNode([(TaskStatus.FAILED, 55)], after_restart=[(TaskStatus.FAILED, 60)]))
    _, sid = _flight_with_images(client, analyst, operator)
    run_pending()
    # The fallback run fails too: the flight fails once (a PipelineError is not retried), with ODM's reason.
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert s["status"] == "FAILED" and s["job"]["attempts"] == 1
    assert s["error"].startswith("OpenDroneMap could not build the orthomosaic")
    assert len(node.created) == 1 and len(next(iter(node.tasks.values())).restarts) == 1


def test_out_of_memory_is_explained(tmp_path, monkeypatch, odm):
    monkeypatch.setattr(get_settings(), "odm_fallback_sfm", "")
    node = odm(FakeNode([(TaskStatus.FAILED, 55)]))
    inp = _inputs(tmp_path)
    eng = NodeODMOrthomosaicEngine()
    orig = node.create_task

    def create(*a, **k):
        t = orig(*a, **k)
        t.output_lines += ["Traceback (most recent call last):", "MemoryError: Unable to allocate 11.2 GiB"]
        return t

    node.create_task = create
    from app.pipeline.base import PipelineError

    with pytest.raises(PipelineError, match="ran out of memory"):
        eng.build(inp, lambda p: None)


def test_unreachable_node_is_retried_then_reported(client, analyst, operator, odm):
    odm(FakeNode([(TaskStatus.COMPLETED, 100)], info_error=NodeConnectionError("Connection refused")))
    _, sid = _flight_with_images(client, analyst, operator)
    run_pending()
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert s["status"] == "FAILED" and s["job"]["attempts"] == get_settings().job_max_attempts
    assert "is not reachable" in s["error"] and "localhost:3000" in s["error"]


def test_retry_reattaches_to_the_running_task(client, analyst, operator, odm):
    lost = [NodeConnectionError("timeout")] * 13  # the node goes silent for longer than the engine waits
    node = odm(FakeNode([(TaskStatus.RUNNING, 20), *lost, (TaskStatus.RUNNING, 70), (TaskStatus.COMPLETED, 100)]))
    _, sid = _flight_with_images(client, analyst, operator)
    run_pending()
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert s["status"] == "READY", s["error"]
    assert len(node.created) == 1  # the images were uploaded once
    assert s["job"]["attempts"] == 2


def test_finished_orthophoto_is_reused_on_a_later_retry(tmp_path, odm):
    node = odm(FakeNode([(TaskStatus.COMPLETED, 100)]))
    inp = _inputs(tmp_path)
    eng = NodeODMOrthomosaicEngine()
    first = eng.build(inp, lambda p: None)
    assert first.gsd_cm == 0.42 and first.meta["geotiff"]["epsg"] == 32643
    node.info_error = NodeConnectionError("offline")  # the node is not even asked the second time
    again = eng.build(inp, lambda p: None)
    assert again.path == first.path and node.downloads == 1
