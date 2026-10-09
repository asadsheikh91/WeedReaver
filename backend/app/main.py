"""Application factory. Run with `uvicorn app.main:app`."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from sqlalchemy import text

from app import __version__
from app.api.routes import auth, devices, exports, fields, insight, scans, settings, surveys, treatments, users
from app.core import clock
from app.core.config import get_settings
from app.core.errors import install_error_handlers
from app.core.logging import RequestContextMiddleware, configure_logging
from app.db.session import get_engine, session_scope

log = logging.getLogger("weedreaver")

DESCRIPTION = """
The single backend behind the WeedReaver field app (Android) and the web dashboard.

**The web decides, the phone does.** Definitions (boundaries, the prescription threshold,
flights, review outcomes, label data, exports) are owned by the dashboard. Observations (scans,
zones treated, products applied, verifications) are owned by the phone. Sync settles conflicts
by that ownership, never by last-write-wins.

Authenticate with `POST /auth/login`, then send `Authorization: Bearer <accessToken>`. The field
app also sends `X-Device-ID` (returned at login when it passes `device`).
"""


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    s = get_settings()
    if s.auto_create_schema:
        from app.db.base import Base
        import app.models  # noqa: F401  (registers tables)

        Base.metadata.create_all(get_engine())
    worker = None
    if s.embedded_worker and s.environment != "test":
        from app.pipeline.runner import Worker, engines

        engines()  # fail fast on a misconfigured engine path
        worker = Worker()
        worker.start()
    log.info("WeedReaver API %s started (%s)", __version__, s.environment)
    try:
        yield
    finally:
        if worker is not None:
            worker.stop()


def create_app() -> FastAPI:
    s = get_settings()
    configure_logging(s.log_level, s.log_json)
    app = FastAPI(
        title="WeedReaver API",
        version=__version__,
        description=DESCRIPTION,
        lifespan=lifespan,
        docs_url=f"{s.api_prefix}/docs" if s.docs_enabled else None,
        redoc_url=f"{s.api_prefix}/redoc" if s.docs_enabled else None,
        openapi_url=f"{s.api_prefix}/openapi.json" if s.docs_enabled else None,
    )
    install_error_handlers(app)
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=s.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["X-Request-ID", "Content-Disposition"],
    )
    app.add_middleware(RequestContextMiddleware)

    api = APIRouter(prefix=s.api_prefix)
    for r in (auth, users, settings, fields, surveys, scans, treatments, devices, exports, insight):
        api.include_router(r.router)
    app.include_router(api)

    @app.get("/health", tags=["health"], summary="Liveness")
    def health() -> dict:
        return {"status": "ok", "version": __version__, "time": clock.now().isoformat()}

    @app.get("/ready", tags=["health"], summary="Readiness: the database answers")
    def ready() -> dict:
        with session_scope() as db:
            db.execute(text("SELECT 1"))
        return {"status": "ready"}

    return app


app = create_app()
