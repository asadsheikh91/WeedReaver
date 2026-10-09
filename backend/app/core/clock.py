"""The one place the backend reads the time.

All timestamps are stored and returned as timezone-aware UTC. The station's local zone is
used only where the domain is local: seed dates, "today", and which Rabi season a date
belongs to.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from functools import lru_cache
from zoneinfo import ZoneInfo

from app.core.config import get_settings

UTC = timezone.utc


@lru_cache
def station_tz() -> ZoneInfo:
    return ZoneInfo(get_settings().station_timezone)


@lru_cache
def _offset() -> timedelta:
    """Fixed offset that moves "today" to the configured demo date, keeping time of day."""
    pinned = get_settings().demo_clock_date
    if pinned is None:
        return timedelta(0)
    real = datetime.now(station_tz())
    demo = datetime.combine(pinned, real.timetz())
    return demo - real


def now() -> datetime:
    return datetime.now(UTC) + _offset()


def reset_clock_cache() -> None:
    _offset.cache_clear()
    station_tz.cache_clear()


def local(day: int, month: int, year: int, hour: int = 9, minute: int = 0) -> datetime:
    """A station-local wall-clock time, as aware UTC."""
    return datetime(year, month, day, hour, minute, tzinfo=station_tz()).astimezone(UTC)


def local_date(dt: datetime) -> date:
    return ensure_utc(dt).astimezone(station_tz()).date()


def today() -> date:
    return local_date(now())


def start_of_local_day(d: date) -> datetime:
    return datetime.combine(d, time(0, 0), tzinfo=station_tz()).astimezone(UTC)


def ensure_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def season_of(dt: datetime | date) -> str:
    """Agricultural year label, e.g. a date in Jan 2027 belongs to "2026-27".

    The Rabi (winter) crop is sown in Oct-Dec and harvested in Apr-May, so the year turns
    over in July.
    """
    d = dt if isinstance(dt, date) and not isinstance(dt, datetime) else local_date(dt)  # type: ignore[arg-type]
    y = d.year if d.month >= 7 else d.year - 1
    return f"{y}-{(y + 1) % 100:02d}"


def ms(dt: datetime | None) -> int | None:
    return None if dt is None else int(ensure_utc(dt).timestamp() * 1000)
