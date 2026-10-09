"""In-process sliding-window rate limiter for authentication endpoints.

Adequate for a single API process. Behind several replicas, put the limit at the reverse
proxy (or swap this for a Redis-backed limiter with the same interface).
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

from app.core.errors import RateLimited


class SlidingWindowLimiter:
    def __init__(self, limit: int, window_seconds: float):
        self.limit = limit
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def hit(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            q = self._hits[key]
            while q and now - q[0] > self.window:
                q.popleft()
            if len(q) >= self.limit:
                retry = int(self.window - (now - q[0])) + 1
                raise RateLimited("Too many attempts. Try again later.", details={"retryAfterSeconds": retry})
            q.append(now)
            if len(self._hits) > 50_000:  # bound memory under a credential-stuffing burst
                for k in [k for k, v in self._hits.items() if not v][:10_000]:
                    del self._hits[k]

    def reset(self, key: str) -> None:
        with self._lock:
            self._hits.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._hits.clear()
