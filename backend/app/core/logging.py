"""Logging setup and the request-id / access-log middleware."""

from __future__ import annotations

import contextvars
import json
import logging
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")


class _RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        return True


class _JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        out = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
            "request_id": getattr(record, "request_id", "-"),
        }
        if record.exc_info:
            out["exc"] = self.formatException(record.exc_info)
        return json.dumps(out)


def configure_logging(level: str = "INFO", as_json: bool = False) -> None:
    handler = logging.StreamHandler()
    handler.addFilter(_RequestIdFilter())
    handler.setFormatter(
        _JsonFormatter() if as_json else logging.Formatter("%(asctime)s %(levelname)-7s [%(request_id)s] %(name)s: %(message)s")
    )
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
    logging.getLogger("uvicorn.access").disabled = True


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Tags every request with an id (honouring an incoming X-Request-ID) and logs it."""

    async def dispatch(self, request: Request, call_next) -> Response:  # type: ignore[override]
        rid = request.headers.get("x-request-id") or uuid.uuid4().hex[:16]
        token = request_id_var.set(rid[:64])
        start = time.perf_counter()
        try:
            response = await call_next(request)
            elapsed = (time.perf_counter() - start) * 1000
            response.headers["X-Request-ID"] = rid[:64]
            logging.getLogger("weedreaver.access").info(
                "%s %s %s %.1fms", request.method, request.url.path, response.status_code, elapsed
            )
            return response
        finally:
            request_id_var.reset(token)
