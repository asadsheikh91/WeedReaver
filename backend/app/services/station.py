"""Station-wide definitions: organisation, season, the published prescription threshold."""

from __future__ import annotations

from sqlalchemy.orm import Session

from app.core import clock
from app.core.config import get_settings
from app.models import StationSettings
from app.schemas.system import StationOut

DEFAULTS = {
    "org_name": "Pindi Bhattian field station",
    "season_label": "Rabi 2026-27",
    "threshold_pct": 10.0,
    "default_grid_m": 2,
    "default_units": "local",
    "leaf_abstain_below": 0.65,
    "model_aerial": "wr-seg-deeplabv3p-r50 v0.4.1",
    "model_leaf": "wr-leaf-yolo11n-int8 v0.3.2",
    "leaf_model_size_mb": 12.4,
    "app_latest_version": "0.9.4 (212)",
    "app_min_version": "0.9.0 (190)",
    "season_length_days": 154,
}


def get_station(db: Session) -> StationSettings:
    row = db.get(StationSettings, 1)
    if row is None:
        row = StationSettings(id=1, **DEFAULTS)
        db.add(row)
        db.flush()
    return row


def station_out(row: StationSettings) -> StationOut:
    s = get_settings()
    return StationOut(
        org_name=row.org_name,
        season_label=row.season_label,
        threshold_pct=row.threshold_pct,
        threshold_published_at=row.threshold_published_at,
        threshold_published_by=row.threshold_published_by,
        threshold_min_pct=s.threshold_min_pct,
        threshold_max_pct=s.threshold_max_pct,
        default_grid_m=row.default_grid_m,
        default_units=row.default_units,  # type: ignore[arg-type]
        leaf_abstain_below=row.leaf_abstain_below,
        model_aerial=row.model_aerial,
        model_leaf=row.model_leaf,
        leaf_model_size_mb=row.leaf_model_size_mb,
        app_latest_version=row.app_latest_version,
        app_min_version=row.app_min_version,
        season_length_days=row.season_length_days,
        timezone=s.station_timezone,
        server_time=clock.now(),
        updated_at=row.updated_at,
    )


def version_tuple(v: str | None) -> tuple[int, ...]:
    """'0.9.4 (212)' -> (0, 9, 4, 212); used to tell a phone an update is available."""
    if not v:
        return ()
    out: list[int] = []
    num = ""
    for ch in v:
        if ch.isdigit():
            num += ch
        elif num:
            out.append(int(num))
            num = ""
    if num:
        out.append(int(num))
    return tuple(out)
