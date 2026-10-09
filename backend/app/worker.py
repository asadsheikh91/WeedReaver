"""Standalone processing worker: `python -m app.worker`.

Run this (one or more instances) and set WR_EMBEDDED_WORKER=false on the API when the API is
scaled beyond one process.
"""

from __future__ import annotations

import signal

from app.core.config import get_settings
from app.core.logging import configure_logging
from app.pipeline.runner import Worker


def main() -> None:
    s = get_settings()
    configure_logging(s.log_level, s.log_json)
    w = Worker()

    def _stop(*_: object) -> None:
        w.stop(timeout=0)

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    w.loop()


if __name__ == "__main__":
    main()
