"""Runtime configuration. Every value can be set from the environment or a `.env` file."""

from __future__ import annotations

from datetime import date
from functools import lru_cache
from pathlib import Path
import json
from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

INSECURE_DEFAULT_SECRET = "change-me-in-production-this-is-not-a-secret"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="WR_", extra="ignore")

    # ---- service ------------------------------------------------------------------------
    environment: Literal["development", "test", "production"] = "development"
    api_prefix: str = "/api/v1"
    docs_enabled: bool = True  # interactive docs at {api_prefix}/docs
    log_level: str = "INFO"
    log_json: bool = False

    # ---- database -----------------------------------------------------------------------
    # PostgreSQL in production (postgresql+psycopg://user:pass@host/db); SQLite works for
    # local development and the test suite.
    database_url: str = "sqlite:///./weedreaver.db"
    database_echo: bool = False
    database_pool_size: int = 10
    database_max_overflow: int = 20
    auto_create_schema: bool = False

    # ---- security -----------------------------------------------------------------------
    secret_key: str = INSECURE_DEFAULT_SECRET
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = 15
    # Long, because a phone may stay out of signal for days between syncs.
    refresh_token_days: int = 60
    invitation_days: int = 7
    password_reset_minutes: int = 30
    login_rate_limit: int = 10  # attempts per window per (ip, email)
    login_rate_window_seconds: int = 300
    cors_origins: Annotated[list[str], NoDecode] = Field(default_factory=lambda: ["http://localhost:5173", "http://127.0.0.1:5173"])
    # Where invitation and reset links point (the web dashboard).
    public_web_url: str = "http://localhost:5173"

    # ---- storage ------------------------------------------------------------------------
    storage_dir: Path = Path("./storage")
    max_flight_image_mb: int = 80
    max_flight_images: int = 5000
    max_scan_photo_mb: int = 12
    max_boundary_file_kb: int = 2048

    # ---- clock --------------------------------------------------------------------------
    # The station's local time zone. Seed dates and "season" boundaries are local.
    station_timezone: str = "Asia/Karachi"
    # Optional: pin "today" for demonstrations (the field app and dashboard demo date is
    # 2027-01-22). The real time of day still advances. Leave unset in production.
    demo_clock_date: date | None = None

    # ---- processing pipeline ------------------------------------------------------------
    # Dotted paths "module:Class". Replace with your own orthomosaic / segmentation engines.
    orthomosaic_engine: str = "app.pipeline.orthomosaic:SimulatedOrthomosaicEngine"
    segmentation_engine: str = "app.pipeline.segmentation:SimulatedSegmentationEngine"
    # Run the job worker inside the API process. Turn off and run `python -m app.worker`
    # separately when deploying more than one API replica.
    embedded_worker: bool = True
    worker_poll_seconds: float = 2.0
    simulated_pipeline_seconds: float = 40.0
    job_max_attempts: int = 3
    job_stale_minutes: int = 30

    # ---- OpenDroneMap (app.pipeline.orthomosaic:NodeODMOrthomosaicEngine) -----------------
    # The NodeODM server that stitches flights. Run it on the station PC, next to the images:
    #   docker run -d -p 3000:3000 opendronemap/nodeodm --token <token>
    nodeodm_url: str = "http://localhost:3000"
    nodeodm_token: str | None = None
    # Orthophoto resolution in cm/px. Unset: the flight's own GSD (what the camera resolved),
    # which is what a weed model wants; ODM never goes finer than its own GSD estimate.
    odm_orthophoto_cm: float | None = None
    # How far beyond the field boundary the orthophoto is kept, in metres (cropping and tiles).
    odm_boundary_margin_m: float = 15.0
    # Extra ODM options as JSON, applied over the defaults, e.g. {"feature-quality": "medium"}
    # or {"split": 300, "split-overlap": 60} for flights larger than the node's RAM allows.
    odm_options: dict = Field(default_factory=dict)
    # If a fast run fails (sfm-algorithm planar), restart once with this one on the node,
    # without uploading again. Empty to disable.
    odm_fallback_sfm: str = "incremental"
    odm_parallel_uploads: int = 4
    odm_poll_seconds: float = 5.0
    # A task still running after this long is cancelled and the flight marked failed.
    odm_max_hours: float = 12.0
    # Delete the task (and the node's copy of the images) once its orthophoto is downloaded.
    odm_remove_finished_tasks: bool = True

    # ---- analysis -----------------------------------------------------------------------
    threshold_min_pct: int = 2
    threshold_max_pct: int = 40
    analysis_cache_size: int = 96

    # ---- weather ------------------------------------------------------------------------
    weather_provider: Literal["static", "open-meteo"] = "static"
    weather_cache_minutes: int = 30
    weather_timeout_seconds: float = 6.0

    # ---- mail ---------------------------------------------------------------------------
    mail_backend: Literal["console", "smtp"] = "console"
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_user: str | None = None
    smtp_password: str | None = None
    smtp_from: str = "WeedReaver <no-reply@weedreaver.local>"
    smtp_starttls: bool = True

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _split_origins(cls, v: object) -> object:
        if isinstance(v, str):
            if v.strip().startswith("["):
                return json.loads(v)
            return [o.strip() for o in v.split(",") if o.strip()]
        return v

    @model_validator(mode="after")
    def _production_guards(self) -> "Settings":
        if self.environment == "production":
            if self.secret_key == INSECURE_DEFAULT_SECRET or len(self.secret_key) < 32:
                raise ValueError("WR_SECRET_KEY must be set to a random value of at least 32 characters in production")
            if self.demo_clock_date is not None:
                raise ValueError("WR_DEMO_CLOCK_DATE must not be set in production")
        return self

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    return Settings()
