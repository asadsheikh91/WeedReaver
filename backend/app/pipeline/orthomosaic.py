"""Photogrammetry: flight images -> orthomosaic.

`NodeODMOrthomosaicEngine` stitches flights with OpenDroneMap through a NodeODM server.
`SimulatedOrthomosaicEngine` lets the whole system run end-to-end without one (demos, tests).
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import shutil
import time
import uuid as uuidlib
from pathlib import Path

from app.core.config import get_settings
from app.domain import geo
from app.pipeline import geotiff
from app.pipeline.base import FlightInputs, OrthomosaicResult, PipelineError, ProgressFn

log = logging.getLogger("weedreaver.pipeline.odm")


def gsd_for(altitude_m: float) -> float:
    """Ground sampling distance of the Mavic 3M wide RGB camera: 0.42 cm/px at 15 m AGL."""
    return round(altitude_m * 0.028, 2)


class SimulatedOrthomosaicEngine:
    """Passes time and reports progress; produces no raster. For demonstrations and tests."""

    name = "Simulated photogrammetry"
    requires_images = False

    def build(self, inputs: FlightInputs, progress: ProgressFn) -> OrthomosaicResult:
        # Photogrammetry is the long step: about 55% of the configured pipeline time.
        total = max(0.0, get_settings().simulated_pipeline_seconds) * 0.55
        steps = 20
        start = min(max(inputs.resume_from, 0.0), 1.0)
        remaining = int(round(steps * (1.0 - start)))
        for i in range(1, remaining + 1):
            if total:
                time.sleep(total / steps)
            progress(start + (1.0 - start) * i / max(1, remaining))
        progress(1.0)
        return OrthomosaicResult(
            path=None,
            gsd_cm=gsd_for(inputs.altitude_m),
            pipeline="Simulated · OpenDroneMap stand-in",
            meta={"images": inputs.image_count, "simulated": True},
        )




# ----------------------------------------------------------------------------- OpenDroneMap

#: Where each part of the work sits within this step's 0..1 progress.
UPLOAD, PROCESS, DOWNLOAD = (0.0, 0.15), (0.15, 0.95), (0.95, 1.0)
ORTHOPHOTO = Path("odm_orthophoto") / "odm_orthophoto.tif"
REPORT = Path("odm_report") / "report.pdf"
STATE_FILE = "nodeodm-task.json"

# Ordered: the first match explains the failure. Matched against ODM's console output.
_FAILURE_HINTS: list[tuple[tuple[str, ...], str]] = [
    (("not enough images",),
     "too few images to reconstruct; a mapping flight needs dozens of overlapping photos"),
    (("memoryerror", "out of memory", "cannot allocate memory", "bad_alloc", "killed"),
     "the processing node ran out of memory. Give it more RAM or swap, or split the flight with "
     'WR_ODM_OPTIONS={"split": 300, "split-overlap": 60}'),
    (("no space left on device",),
     "the processing node ran out of disk space"),
    (("reconstruction", "not enough matches", "no matches"),
     "the images could not be matched into one reconstruction. Check the overlap (front 75%, side 65% "
     "or more) and that every image carries GPS"),
]


def _band(lo_hi: tuple[float, float], local: float) -> float:
    lo, hi = lo_hi
    return lo + (hi - lo) * min(1.0, max(0.0, local))


def _convex_hull(points: list[geo.Pt]) -> list[geo.Pt]:
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o: geo.Pt, a: geo.Pt, b: geo.Pt) -> float:
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list[geo.Pt] = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list[geo.Pt] = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def _grow(hull: list[geo.Pt], margin: float) -> list[geo.Pt]:
    """Pushes every edge of a convex polygon `margin` metres outwards (mitred corners, capped)."""
    if margin <= 0 or len(hull) < 3:
        return list(hull)
    cx, cy = geo.centroid(hull)
    normals: list[geo.Pt] = []
    for k in range(len(hull)):
        (ax, ay), (bx, by) = hull[k], hull[(k + 1) % len(hull)]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy) or 1.0
        nx, ny = dy / length, -dx / length
        if nx * ((ax + bx) / 2 - cx) + ny * ((ay + by) / 2 - cy) < 0:
            nx, ny = -nx, -ny
        normals.append((nx, ny))
    out: list[geo.Pt] = []
    for k, (px, py) in enumerate(hull):
        n1, n2 = normals[k - 1], normals[k]  # the edges ending and starting at this vertex
        denom = 1.0 + n1[0] * n2[0] + n1[1] * n2[1]
        scale = margin / denom if denom > 0.25 else margin * 4.0  # very sharp corner: cap the mitre
        out.append((px + (n1[0] + n2[0]) * scale, py + (n1[1] + n2[1]) * scale))
    return out


def boundary_geojson(inputs: FlightInputs, margin_m: float) -> dict:
    """The field's convex hull plus a margin, in WGS84 (lon, lat): the area worth keeping.

    For cropping the orthophoto and its tiles. It is not sent to ODM as `boundary`, because
    NodeODM (3.6) removes every double quote from option values, which breaks the JSON.
    """
    ring = _grow(_convex_hull([(float(x), float(y)) for x, y in inputs.boundary]), margin_m)
    coords = [[round(lon, 8), round(lat, 8)]
              for lat, lon in (geo.to_latlon(inputs.anchor_lat, inputs.anchor_lon, p) for p in ring)]
    coords.append(coords[0])
    return {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"field": inputs.field_id},
         "geometry": {"type": "Polygon", "coordinates": [coords]}},
    ]}


def odm_options(inputs: FlightInputs) -> dict:
    """ODM settings for a nadir, fixed-altitude mapping flight over flat wheat.

    - fast-orthophoto: no dense point cloud or 3D model. A flat field does not need them, and
      they are the bulk of ODM's time and memory.
    - sfm-algorithm planar: ODM's fast path for fixed-altitude, nadir-only flights.
    - auto-boundary: reconstruct only the area the camera positions cover (the flight plan is
      the field), not the far background. The exact field outline is applied on our side:
      NodeODM strips the double quotes out of option values before calling ODM, so a GeoJSON
      boundary cannot be passed through it (see boundary_geojson).
    - orthophoto-resolution: the flight's own GSD unless configured; ODM caps it at its estimate.
    - cog + build-overviews: a cloud-optimised GeoTIFF, so viewers read only what they show.
    Anything here can be overridden with WR_ODM_OPTIONS.
    """
    s = get_settings()
    opts: dict = {
        "fast-orthophoto": True,
        "sfm-algorithm": "planar",
        "orthophoto-resolution": float(s.odm_orthophoto_cm or max(gsd_for(inputs.altitude_m), 0.1)),
        "cog": True,
        "build-overviews": True,
        "skip-3dmodel": True,
        "optimize-disk-space": True,
        "auto-boundary": True,
    }
    opts.update(s.odm_options or {})
    return opts


def images_fingerprint(paths: list[Path]) -> str:
    h = hashlib.sha1()
    for p in sorted(paths, key=lambda q: q.name):
        h.update(f"{p.name}:{p.stat().st_size if p.exists() else -1}\n".encode())
    return h.hexdigest()


class NodeODMOrthomosaicEngine:
    """Stitches a flight with OpenDroneMap on a NodeODM server (WR_NODEODM_URL).

    Upload (parallel, retried per file) -> ODM processes -> download the orthophoto only. The
    task id is kept in the flight's work folder, so a retry after a crash or a lost connection
    re-attaches to the running task instead of uploading again, and a retry after a later step
    failed reuses the orthophoto already downloaded. A failed fast run is restarted once on the
    node with a more robust structure-from-motion algorithm, again without a new upload.

    Failures the operator should read raise PipelineError (the flight is marked failed with the
    message); an unreachable node raises ConnectionError, which the runner retries.
    """

    name = "OpenDroneMap · NodeODM"
    requires_images = True

    def build(self, inputs: FlightInputs, progress: ProgressFn) -> OrthomosaicResult:
        try:
            from pyodm import Node
            from pyodm.exceptions import NodeConnectionError, NodeResponseError, NodeServerError, OdmError
        except ImportError as exc:  # pragma: no cover - the dependency is in requirements.txt
            raise PipelineError("pyodm is not installed on the processing server (pip install pyodm)") from exc

        s = get_settings()
        workdir = inputs.workdir
        workdir.mkdir(parents=True, exist_ok=True)
        fingerprint = images_fingerprint(inputs.image_paths)
        state = self._load_state(workdir)

        # An earlier attempt already produced the orthophoto from these same images.
        done = workdir / "odm" / ORTHOPHOTO
        if state.get("done") and state.get("fingerprint") == fingerprint and done.is_file():
            progress(1.0)
            return self._result(done, inputs, state)

        node = Node.from_url(s.nodeodm_url)
        if s.nodeodm_token:
            node.token = s.nodeodm_token
        try:
            node_info = node.info()
        except (NodeConnectionError, NodeServerError) as exc:
            raise ConnectionError(f"The OpenDroneMap node at {s.nodeodm_url} is not reachable: {exc}") from exc
        except NodeResponseError as exc:
            raise PipelineError(f"The OpenDroneMap node refused the request: {exc}") from exc
        if node_info.max_images and len(inputs.image_paths) > node_info.max_images:
            raise PipelineError(f"This flight has {len(inputs.image_paths)} images; the processing node accepts at most "
                                f"{node_info.max_images}")
        engine = f"OpenDroneMap {node_info.engine_version} · NodeODM {node_info.version}"

        try:
            task = self._attach(node, state, fingerprint, s.nodeodm_url)
            if task is None:
                task = self._create(node, inputs, progress, state, fingerprint, s.nodeodm_url)
            state["engine"] = engine
            info = self._wait(task, progress, state, workdir)
            path = self._download(task, workdir, progress)
        except (NodeConnectionError, NodeServerError) as exc:
            raise ConnectionError(f"Lost the OpenDroneMap node at {s.nodeodm_url}: {exc}") from exc
        except NodeResponseError as exc:
            raise PipelineError(f"OpenDroneMap rejected the task: {exc}") from exc

        state.update(done=True, processingSeconds=round(max(0, info.processing_time or 0) / 1000))
        if s.odm_remove_finished_tasks:
            try:
                task.remove()
                state["removed"] = True
            except OdmError as exc:  # the node cleans up old tasks by itself anyway
                log.warning("Could not remove ODM task %s: %s", task.uuid, exc)
        self._save_state(workdir, state)
        progress(1.0)
        return self._result(path, inputs, state)

    # ------------------------------------------------------------------ steps

    def _attach(self, node, state: dict, fingerprint: str, url: str):
        from pyodm.exceptions import NodeResponseError
        from pyodm.types import TaskStatus

        if not state.get("uuid") or state.get("url") != url or state.get("fingerprint") != fingerprint:
            return None
        task = node.get_task(state["uuid"])
        try:
            info = task.info()
        except NodeResponseError:  # the node no longer knows it (cleaned up, or a different node)
            return None
        if info.status in (TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.COMPLETED):
            log.info("Re-attached to ODM task %s (%s)", task.uuid, info.status.name)
            return task
        return None  # failed or cancelled: start over with a fresh task

    def _create(self, node, inputs: FlightInputs, progress: ProgressFn, state: dict, fingerprint: str, url: str):
        s = get_settings()
        options = odm_options(inputs)
        task_uuid = str(uuidlib.uuid4())
        state.clear()
        state.update(uuid=task_uuid, url=url, fingerprint=fingerprint, options=options, fallbackUsed=False,
                     images=len(inputs.image_paths), startedAt=time.time())
        self._save_state(inputs.workdir, state)
        progress(_band(UPLOAD, 0.0))
        log.info("Uploading %d images of %s to ODM task %s", len(inputs.image_paths), inputs.survey_id, task_uuid)
        return node.create_task(
            [str(p) for p in inputs.image_paths], options,
            name=f"WeedReaver {inputs.survey_id} · {inputs.field_id} · {inputs.role}",
            progress_callback=lambda pct: progress(_band(UPLOAD, pct / 100.0)),
            skip_post_processing=True,  # no web tiles or point-cloud tiles on the node: only the GeoTIFF
            outputs=[ORTHOPHOTO.as_posix(), REPORT.as_posix()],
            parallel_uploads=max(1, s.odm_parallel_uploads),
            task_uuid=task_uuid,
        )

    def _wait(self, task, progress: ProgressFn, state: dict, workdir: Path):
        from pyodm.exceptions import NodeConnectionError
        from pyodm.types import TaskStatus

        s = get_settings()
        deadline = float(state.get("startedAt") or time.time()) + s.odm_max_hours * 3600
        misses = 0
        while True:
            try:
                info = task.info()
                misses = 0
            except NodeConnectionError:
                misses += 1
                if misses > 12:  # about a minute of silence at the default poll
                    raise
                time.sleep(s.odm_poll_seconds)
                continue
            # Called every poll even when ODM's percentage stands still: it is the job's heartbeat.
            progress(_band(PROCESS, (info.progress or 0) / 100.0))
            if info.status == TaskStatus.COMPLETED:
                return info
            if info.status == TaskStatus.CANCELED:
                raise PipelineError("Processing was cancelled on the OpenDroneMap node")
            if info.status == TaskStatus.FAILED:
                options = dict(state.get("options") or {})
                fallback = s.odm_fallback_sfm
                if fallback and fallback != "planar" and not state.get("fallbackUsed") and options.get("sfm-algorithm") == "planar":
                    log.warning("ODM task %s failed on the planar fast path; restarting with %s", task.uuid, fallback)
                    options["sfm-algorithm"] = fallback
                    task.restart(options)  # same images on the node: nothing is uploaded again
                    state.update(options=options, fallbackUsed=True)
                    self._save_state(workdir, state)
                    time.sleep(s.odm_poll_seconds)
                    continue
                raise PipelineError(self._explain(task, info))
            if time.time() > deadline:
                try:
                    task.cancel()
                finally:
                    raise PipelineError(f"OpenDroneMap was still processing after {s.odm_max_hours:g} hours; cancelled")
            time.sleep(s.odm_poll_seconds)

    def _download(self, task, workdir: Path, progress: ProgressFn) -> Path:
        dest = workdir / "odm"
        if dest.exists():
            shutil.rmtree(dest)
        dest.mkdir(parents=True)
        progress(_band(DOWNLOAD, 0.0))
        task.download_assets(str(dest), progress_callback=lambda pct: progress(_band(DOWNLOAD, pct / 100.0)))
        path = dest / ORTHOPHOTO
        if not path.is_file():
            raise PipelineError("OpenDroneMap finished but produced no orthophoto")
        return path

    # ------------------------------------------------------------------ helpers

    def _result(self, path: Path, inputs: FlightInputs, state: dict) -> OrthomosaicResult:
        try:
            tif = geotiff.describe(path)
        except ValueError as exc:
            raise PipelineError(f"OpenDroneMap's orthophoto is not a usable GeoTIFF: {exc}") from exc
        report = path.parent.parent / REPORT
        return OrthomosaicResult(
            path=path,
            gsd_cm=round(tif.gsd_cm(inputs.anchor_lat), 2),
            pipeline=state.get("engine") or self.name,
            meta={
                "task": state.get("uuid"),
                "images": state.get("images", len(inputs.image_paths)),
                "processingSeconds": state.get("processingSeconds"),
                "fallbackUsed": bool(state.get("fallbackUsed")),
                "options": {k: v for k, v in (state.get("options") or {}).items() if k != "boundary"},
                "geotiff": tif.as_meta(),
                "report": str(report) if report.is_file() else None,
            },
        )

    @staticmethod
    def _explain(task, info) -> str:
        from pyodm.exceptions import OdmError

        lines: list[str] = []
        try:
            lines = [str(x) for x in (task.output(line=-120) or [])]
        except OdmError:
            pass
        text = "\n".join(lines).lower() + "\n" + (info.last_error or "").lower()
        for needles, hint in _FAILURE_HINTS:
            if any(n in text for n in needles):
                return f"OpenDroneMap could not build the orthomosaic: {hint}"
        detail = next((ln.strip() for ln in reversed(lines) if any(w in ln.lower() for w in ("error", "exception"))), "")
        detail = detail or (info.last_error or "").strip() or next((ln.strip() for ln in reversed(lines) if ln.strip()), "")
        return f"OpenDroneMap could not build the orthomosaic: {detail[:300] or 'see the node log'}"

    @staticmethod
    def _load_state(workdir: Path) -> dict:
        try:
            return json.loads((workdir / STATE_FILE).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    @staticmethod
    def _save_state(workdir: Path, state: dict) -> None:
        tmp = workdir / (STATE_FILE + ".tmp")
        tmp.write_text(json.dumps(state, indent=2), encoding="utf-8")
        tmp.replace(workdir / STATE_FILE)
