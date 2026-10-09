"""Spray-window conditions (wind, delta T, rain-free hours) for a field.

`static` returns the conditions the field app caches before leaving signal (demo values).
`open-meteo` fetches live conditions (no API key) and caches them. Either way the system shows
the conditions and never decides: spraying is the operator's weather decision.
"""

from __future__ import annotations

import logging
import math
import threading
import time
from datetime import datetime

import httpx

from app.core import clock
from app.core.config import get_settings
from app.schemas.system import ConditionsOut

log = logging.getLogger("weedreaver.weather")
_cache: dict[tuple[float, float], tuple[float, ConditionsOut]] = {}
_lock = threading.Lock()
_DIRS = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")


def wet_bulb_c(temp_c: float, rh: float) -> float:
    """Stull (2011) wet-bulb approximation, valid for 5-99% RH and -20..50 °C."""
    rh = min(99.0, max(5.0, rh))
    return (
        temp_c * math.atan(0.151977 * math.sqrt(rh + 8.313659))
        + math.atan(temp_c + rh)
        - math.atan(rh - 1.676331)
        + 0.00391838 * rh ** 1.5 * math.atan(0.023101 * rh)
        - 4.686035
    )


def assess(wind: float, gust: float, delta_t: float, rain_free: int) -> tuple[str, list[str]]:
    notes: list[str] = []
    poor = marginal = False
    if wind < 3:
        marginal = True
        notes.append("Very light wind: risk of a surface inversion and drift hanging in the air")
    elif wind > 15:
        poor = True
        notes.append("Wind above 15 km/h: drift onto neighbouring crops")
    if gust > 20:
        marginal = True
        notes.append("Gusty")
    if delta_t > 10:
        poor = True
        notes.append("Delta T above 10: droplets evaporate before they land")
    elif delta_t > 8 or delta_t < 2:
        marginal = True
        notes.append("Delta T outside 2-8: marginal droplet survival" if delta_t > 8 else "Delta T below 2: humid, slow drying")
    if rain_free < 6:
        poor = True
        notes.append("Rain expected within 6 hours: wash-off")
    elif rain_free < 12:
        marginal = True
        notes.append("Rain expected within 12 hours")
    if not notes:
        notes.append("Within the usual label conditions")
    return ("poor" if poor else "marginal" if marginal else "good"), notes


def _static() -> ConditionsOut:
    d = get_settings().demo_clock_date
    updated = clock.local(d.day, d.month, d.year, 6, 10) if d else clock.now()
    window, notes = assess(7, 12, 4.2, 36)
    return ConditionsOut(temp_c=14, wind_kmh=7, gust_kmh=12, wind_from="NW", humidity=64, delta_t=4.2, rain_free_hours=36,
                         updated_at=updated, source="Cached at the station", spray_window=window, notes=notes)  # type: ignore[arg-type]


def _open_meteo(lat: float, lon: float) -> ConditionsOut:
    s = get_settings()
    params = {
        "latitude": round(lat, 4), "longitude": round(lon, 4),
        "current": "temperature_2m,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m",
        "hourly": "precipitation", "forecast_days": 3, "timezone": "UTC", "wind_speed_unit": "kmh",
    }
    r = httpx.get("https://api.open-meteo.com/v1/forecast", params=params, timeout=s.weather_timeout_seconds)
    r.raise_for_status()
    data = r.json()
    cur = data["current"]
    temp, rh = float(cur["temperature_2m"]), float(cur["relative_humidity_2m"])
    wind, gust = float(cur["wind_speed_10m"]), float(cur["wind_gusts_10m"])
    direction = _DIRS[int(((float(cur["wind_direction_10m"]) + 22.5) % 360) // 45)]
    now = datetime.fromisoformat(cur["time"]).replace(tzinfo=clock.UTC)
    rain_free = 48
    for t, p in zip(data["hourly"]["time"], data["hourly"]["precipitation"]):
        when = datetime.fromisoformat(t).replace(tzinfo=clock.UTC)
        if when >= now and p is not None and float(p) > 0.1:
            rain_free = max(0, int((when - now).total_seconds() // 3600))
            break
    delta_t = round(temp - wet_bulb_c(temp, rh), 1)
    window, notes = assess(wind, gust, delta_t, rain_free)
    return ConditionsOut(temp_c=round(temp, 1), wind_kmh=round(wind, 1), gust_kmh=round(gust, 1), wind_from=direction,
                         humidity=round(rh), delta_t=delta_t, rain_free_hours=rain_free, updated_at=now,
                         source="Open-Meteo", spray_window=window, notes=notes)  # type: ignore[arg-type]


def conditions(lat: float, lon: float) -> ConditionsOut:
    s = get_settings()
    if s.weather_provider == "static":
        return _static()
    key = (round(lat, 2), round(lon, 2))
    with _lock:
        hit = _cache.get(key)
        if hit and time.monotonic() - hit[0] < s.weather_cache_minutes * 60:
            return hit[1]
    try:
        value = _open_meteo(lat, lon)
    except (httpx.HTTPError, KeyError, ValueError, TypeError):
        log.warning("Weather lookup failed; serving the last known or cached conditions", exc_info=True)
        return hit[1] if hit else _static()
    with _lock:
        _cache[key] = (time.monotonic(), value)
    return value
