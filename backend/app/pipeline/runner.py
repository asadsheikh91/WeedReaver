"""Runs processing jobs: claims one, drives it through the engines, stores and publishes.

Jobs live in the database, so they survive restarts. A job whose worker died (no progress for
WR_JOB_STALE_MINUTES) is re-queued; a failing job is retried up to WR_JOB_MAX_ATTEMPTS times,
then the flight is marked FAILED with the engine's message. Claiming uses
SELECT ... FOR UPDATE SKIP LOCKED on PostgreSQL, so several workers can run side by side.
"""

from __future__ import annotations

import logging
import os
import shutil
import socket
import threading
import time
from datetime import timedelta
from functools import lru_cache

from sqlalchemy import select

from app.core import clock
from app.core.config import get_settings
from app.db.session import session_scope
from app.domain.enums import JobStatus, SurveyRole, SurveyStatus
from app.models import ProcessingJob, Survey, SurveyImage, TreatmentZone
from app.pipeline.base import (
    FlightInputs, OrthomosaicEngine, OrthomosaicResult, PipelineError, SegmentationEngine, SegmentationInputs, load_engine,
)
from app.services import analysis, journal
from app.services.storage import get_storage

log = logging.getLogger("weedreaver.pipeline")

#: Global progress bands of each step, matching what the dashboard animates.
STAGES = {"Preparing": (0.0, 0.05), "Photogrammetry": (0.05, 0.6), "Segmentation": (0.6, 0.9), "Zoning": (0.9, 1.0)}


@lru_cache
def engines() -> tuple[OrthomosaicEngine, SegmentationEngine]:
    s = get_settings()
    ortho = load_engine(s.orthomosaic_engine)
    seg = load_engine(s.segmentation_engine)
    if not isinstance(ortho, OrthomosaicEngine):
        raise TypeError(f"{s.orthomosaic_engine} does not implement OrthomosaicEngine")
    if not isinstance(seg, SegmentationEngine):
        raise TypeError(f"{s.segmentation_engine} does not implement SegmentationEngine")
    return ortho, seg


def worker_id() -> str:
    return f"{socket.gethostname()}:{os.getpid()}:{threading.get_ident()}"


class _Progress:
    """Writes progress for one step, mapped into the job's global band, at most ~2x a second."""

    def __init__(self, job_id: str, survey_id: str, stage: str):
        self.job_id, self.survey_id, self.stage = job_id, survey_id, stage
        self.lo, self.hi = STAGES[stage]
        self._last_t = 0.0
        self._last_p = -1.0

    def resume_point(self, global_p: float) -> float:
        if global_p <= self.lo:
            return 0.0
        return min(1.0, (global_p - self.lo) / (self.hi - self.lo))

    def __call__(self, local: float) -> None:
        p = self.lo + (self.hi - self.lo) * min(1.0, max(0.0, float(local)))
        now = time.monotonic()
        if p - self._last_p < 0.01 and now - self._last_t < 0.5 and local < 1.0:
            return
        self._last_p, self._last_t = p, now
        self.write(p)

    def write(self, p: float) -> None:
        with session_scope() as db:
            job = db.get(ProcessingJob, self.job_id)
            survey = db.get(Survey, self.survey_id)
            if job is None or survey is None:
                return
            job.progress = max(job.progress, p)
            job.stage = self.stage
            job.locked_at = clock.now()
            survey.progress = max(survey.progress, p)
            survey.stage = self.stage
            survey.status = SurveyStatus.PROCESSING


def recover_stale() -> int:
    cutoff = clock.now() - timedelta(minutes=get_settings().job_stale_minutes)
    n = 0
    with session_scope() as db:
        for job in db.scalars(select(ProcessingJob).where(ProcessingJob.status == JobStatus.RUNNING)).all():
            if job.locked_at is None or job.locked_at < cutoff:
                job.status = JobStatus.QUEUED
                job.locked_by = None
                job.error = "Re-queued after the worker stopped responding"
                n += 1
    if n:
        log.warning("Re-queued %d stale processing job(s)", n)
    return n


def claim() -> str | None:
    with session_scope() as db:
        q = (
            select(ProcessingJob)
            .where(ProcessingJob.status == JobStatus.QUEUED)
            .order_by(ProcessingJob.created_at)
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        job = db.scalars(q).first()
        if job is None:
            return None
        job.status = JobStatus.RUNNING
        job.attempts += 1
        job.locked_by = worker_id()
        job.locked_at = clock.now()
        job.started_at = job.started_at or clock.now()
        return job.id


def run(job_id: str) -> bool:
    """Process one claimed job. Returns True on success."""
    ortho_engine, seg_engine = engines()
    settings = get_settings()
    storage = get_storage()

    with session_scope() as db:
        job = db.get(ProcessingJob, job_id)
        if job is None:
            return False
        survey = db.get(Survey, job.survey_id)
        if survey is None:
            job.status = JobStatus.FAILED
            job.error = "Flight no longer exists"
            return False
        field = survey.season.field
        survey_id, role = survey.id, survey.role
        start_p = job.progress
        survey.status = SurveyStatus.PROCESSING
        survey.stage = "Preparing"
        survey.error = None
        image_paths = [
            storage.path(img.storage_key)
            for img in db.scalars(select(SurveyImage).where(SurveyImage.survey_id == survey.id).order_by(SurveyImage.id))
        ]
        inputs = FlightInputs(
            survey_id=survey.id, field_id=field.id, role=survey.role, image_paths=image_paths, image_count=survey.images,
            altitude_m=survey.altitude_m, sensor=survey.sensor, boundary=analysis.boundary_of(field),
            anchor_lat=field.lat, anchor_lon=field.lon, workdir=storage.dir(f"flights/{survey.id}/work"),
        )

    try:
        if ortho_engine.requires_images and not inputs.image_paths:
            raise PipelineError("No image files were uploaded for this flight")

        step = _Progress(job_id, survey_id, "Photogrammetry")
        inputs.resume_from = step.resume_point(start_p)
        step(inputs.resume_from)
        ortho: OrthomosaicResult = ortho_engine.build(inputs, step)

        ortho_key = None
        if ortho.path is not None and ortho.path.is_file():
            ortho_key = f"flights/{survey_id}/orthomosaic{ortho.path.suffix or '.tif'}"
            dest = storage.path(ortho_key)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if ortho.path.resolve() != dest.resolve():
                shutil.copyfile(ortho.path, dest)

        seg_step = _Progress(job_id, survey_id, "Segmentation")
        with session_scope() as db:
            survey = db.get(Survey, survey_id)
            assert survey is not None
            field = survey.season.field
            ctx = analysis.context(db, field)
            pre = analysis.surface_for_role(db, ctx, SurveyRole.PRE) if role != SurveyRole.PRE else None
            plus14 = analysis.surface_for_role(db, ctx, SurveyRole.PLUS_14D) if role == SurveyRole.PLUS_28D else None
            existing = analysis.load_surface(db, survey)
            if role == SurveyRole.PRE and existing is None:
                # A re-flight of the pre-treatment survey: the simulator keeps the field's surface.
                prior_pre = next(
                    (s for s in ctx.surveys if s.role == SurveyRole.PRE and s.id != survey_id and s.status == SurveyStatus.READY),
                    None,
                )
                pre = (prior_pre, analysis.load_surface(db, prior_pre)) if prior_pre else None
            states = {
                z.letter: z.state
                for z in db.scalars(select(TreatmentZone).where(TreatmentZone.field_season_id == survey.field_season_id))
            }
            seg_inputs = SegmentationInputs(
                survey_id=survey_id, field_id=field.id, role=role, boundary=analysis.boundary_of(field),
                gate=analysis.gate_of(field), anchor_lat=field.lat, anchor_lon=field.lon,
                seed=int((field.landscape or {}).get("seed", 0)), orthomosaic=ortho, workdir=inputs.workdir,
                pre_surface=pre[1] if pre else None, plus14_surface=plus14[1] if plus14 else None,
                zone_states=states, existing_surface=existing, resume_from=seg_step.resume_point(start_p),
            )
        result = seg_engine.segment(seg_inputs, seg_step)

        zoning = _Progress(job_id, survey_id, "Zoning")
        zoning(0.0)
        with session_scope() as db:
            survey = db.get(Survey, survey_id)
            job = db.get(ProcessingJob, job_id)
            assert survey is not None and job is not None
            field = survey.season.field
            analysis.store_surface(db, survey, result.surface, result.model_version)
            survey.status = SurveyStatus.READY
            survey.progress = 1.0
            survey.stage = "Ready"
            survey.gsd_cm = ortho.gsd_cm or survey.gsd_cm
            survey.pipeline = ortho.pipeline
            survey.model_version = result.model_version
            survey.orthomosaic_key = ortho_key or survey.orthomosaic_key
            survey.processed_at = clock.now()
            survey.error = None
            zones = []
            if role == SurveyRole.PRE:
                zones = analysis.publish_zones(db, field, actor="System")
            job.status = JobStatus.SUCCEEDED
            job.progress = 1.0
            job.stage = "Done"
            job.finished_at = clock.now()
            job.error = None
            job.result = {"gsdCm": survey.gsd_cm, "zones": len(zones), "model": result.model_version, "pipeline": ortho.pipeline}
            journal.change(db, entity="survey", op="ready", entity_id=survey_id, owned_by_mobile=False,
                           summary=f"{field.name} · {survey_id} processed")
            journal.audit(db, "System", "Photogrammetry finished",
                          f"{survey_id} · {field.name} · orthomosaic and {'zones' if role == SurveyRole.PRE else 'follow-up surface'} ready",
                          entity="survey", entity_id=survey_id)
        log.info("Processed flight %s", survey_id)
        return True

    except Exception as exc:  # noqa: BLE001 - every failure must land on the flight
        message = str(exc) if isinstance(exc, PipelineError) else f"{type(exc).__name__}: {exc}"
        if not isinstance(exc, PipelineError):
            log.exception("Processing job %s failed", job_id)
        else:
            log.warning("Processing job %s failed: %s", job_id, message)
        with session_scope() as db:
            job = db.get(ProcessingJob, job_id)
            survey = db.get(Survey, survey_id)
            if job is None or survey is None:
                return False
            job.error = message[:2000]
            job.locked_by = None
            retryable = not isinstance(exc, PipelineError) and job.attempts < settings.job_max_attempts
            if retryable:
                job.status = JobStatus.QUEUED
                survey.status = SurveyStatus.QUEUED
                survey.error = f"Retrying: {message}"[:2000]
            else:
                job.status = JobStatus.FAILED
                job.finished_at = clock.now()
                survey.status = SurveyStatus.FAILED
                survey.stage = "Failed"
                survey.error = message[:2000]
                journal.audit(db, "System", "Flight processing failed", f"{survey_id} · {survey.season.field.name} · {message}"[:500],
                              entity="survey", entity_id=survey_id)
        return False


def run_pending(max_jobs: int | None = None) -> int:
    """Run queued jobs to completion in this thread (CLI, tests). Returns how many ran."""
    n = 0
    while max_jobs is None or n < max_jobs:
        job_id = claim()
        if job_id is None:
            break
        run(job_id)
        n += 1
    return n


class Worker:
    def __init__(self) -> None:
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self.loop, name="weedreaver-worker", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout)

    def loop(self) -> None:
        poll = get_settings().worker_poll_seconds
        last_recover = 0.0
        log.info("Processing worker started (%s)", worker_id())
        while not self._stop.is_set():
            try:
                if time.monotonic() - last_recover > 60:
                    recover_stale()
                    last_recover = time.monotonic()
                job_id = claim()
                if job_id is None:
                    self._stop.wait(poll)
                    continue
                run(job_id)
            except Exception:  # noqa: BLE001 - the loop must survive anything
                log.exception("Worker loop error")
                self._stop.wait(poll)
        log.info("Processing worker stopped")
