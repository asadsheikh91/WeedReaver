"""Thread-safe LRU memo for analysis results.

Rasterising is deterministic in (surface content, grid size, threshold), so results are
cached on the surface fingerprint. Dragging the dashboard's threshold slider re-runs the real
pipeline; repeated positions are served from here.
"""

from __future__ import annotations

import threading
from collections import OrderedDict
from collections.abc import Callable, Hashable
from typing import TypeVar

from app.core.config import get_settings

T = TypeVar("T")


class LRU:
    def __init__(self, size: int):
        self.size = size
        self._data: OrderedDict[Hashable, object] = OrderedDict()
        self._lock = threading.Lock()

    def get_or_make(self, key: Hashable, make: Callable[[], T]) -> T:
        with self._lock:
            if key in self._data:
                self._data.move_to_end(key)
                return self._data[key]  # type: ignore[return-value]
        value = make()  # computed outside the lock; a racing duplicate is harmless
        with self._lock:
            self._data[key] = value
            self._data.move_to_end(key)
            while len(self._data) > self.size:
                self._data.popitem(last=False)
        return value

    def clear(self) -> None:
        with self._lock:
            self._data.clear()


_grids = LRU(get_settings().analysis_cache_size)
_zones = LRU(get_settings().analysis_cache_size)
_surfaces = LRU(64)


def grids() -> LRU:
    return _grids


def zones() -> LRU:
    return _zones


def surfaces() -> LRU:
    return _surfaces


def clear_all() -> None:
    _grids.clear()
    _zones.clear()
    _surfaces.clear()
