"""Each test gets its own copy of a freshly migrated, seeded database and its own storage.

SQLite by default. Set WR_TEST_POSTGRES_URL (e.g. postgresql+psycopg://user@localhost:5432/postgres) to run
the same suite on PostgreSQL: each test then gets a database cloned from a seeded template.
"""

from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path

_TMP = Path(tempfile.mkdtemp(prefix="wr-tests-"))
os.environ.update({
    "WR_ENVIRONMENT": "test",
    "WR_DATABASE_URL": f"sqlite:///{(_TMP / 'template.db').as_posix()}",
    "WR_STORAGE_DIR": str(_TMP / "storage-template"),
    "WR_DEMO_CLOCK_DATE": "2027-01-22",
    "WR_SIMULATED_PIPELINE_SECONDS": "0",
    "WR_EMBEDDED_WORKER": "false",
    "WR_WEATHER_PROVIDER": "static",
    "WR_LOG_LEVEL": "WARNING",
})

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.analysis import cache  # noqa: E402
from app.core import clock  # noqa: E402
from app.core.config import get_settings  # noqa: E402
from app.db.session import reset_engine  # noqa: E402
from app.services import storage  # noqa: E402

PASSWORD = "weedreaver-demo"
ANALYST = "s.anjum@pindibhattian-station.pk"
OPERATOR = "a.mehmood@pindibhattian-station.pk"
ADMIN = "m.rauf@pindibhattian-station.pk"
TRAINEE = "trainee.0212@pindibhattian-station.pk"
API = "/api/v1"


PG_URL = os.environ.get("WR_TEST_POSTGRES_URL")
_counter = iter(range(1, 1_000_000))


def _pg_admin(sql: str) -> None:
    from sqlalchemy import create_engine, text

    eng = create_engine(PG_URL, isolation_level="AUTOCOMMIT")
    with eng.connect() as c:
        c.execute(text(sql))
    eng.dispose()


def _pg_url(name: str) -> str:
    return PG_URL.rsplit("/", 1)[0] + "/" + name  # type: ignore[union-attr]


def _point_at(db: Path | str, storage_dir: Path) -> None:
    os.environ["WR_DATABASE_URL"] = db if isinstance(db, str) else f"sqlite:///{db.as_posix()}"
    os.environ["WR_STORAGE_DIR"] = str(storage_dir)
    get_settings.cache_clear()
    reset_engine()
    storage.get_storage.cache_clear()
    cache.clear_all()
    clock.reset_clock_cache()


@pytest.fixture(scope="session")
def template_db() -> Path | str:
    from app.cli import migrate
    from app.db.session import session_scope
    from app.seed import demo

    if PG_URL:
        _pg_admin("DROP DATABASE IF EXISTS wr_template")
        _pg_admin("CREATE DATABASE wr_template")
        target: Path | str = _pg_url("wr_template")
    else:
        target = _TMP / "template.db"
    _point_at(target, _TMP / "storage-template")
    migrate()
    with session_scope() as db:
        demo.seed(db, PASSWORD)
    reset_engine()
    return target


@pytest.fixture
def client(template_db: Path | str, tmp_path: Path):
    if PG_URL:
        name = f"wr_test_{next(_counter)}"
        _pg_admin(f"DROP DATABASE IF EXISTS {name}")
        _pg_admin(f"CREATE DATABASE {name} TEMPLATE wr_template")
        db: Path | str = _pg_url(name)
    else:
        db = tmp_path / "test.db"
        shutil.copyfile(template_db, db)  # type: ignore[arg-type]
    _point_at(db, tmp_path / "storage")
    from app.api.routes.auth import login_limiter, reset_limiter
    from app.main import app

    login_limiter.clear()
    reset_limiter.clear()
    with TestClient(app) as c:
        yield c
    reset_engine()
    if PG_URL:
        _pg_admin(f"DROP DATABASE IF EXISTS {name}")


def login(client: TestClient, email: str, device: dict | None = None) -> dict:
    body: dict = {"email": email, "password": PASSWORD}
    if device:
        body["device"] = device
    r = client.post(f"{API}/auth/login", json=body)
    assert r.status_code == 200, r.text
    return r.json()


def auth(token_response: dict, device: bool = False) -> dict:
    h = {"Authorization": f"Bearer {token_response['accessToken']}"}
    if device and token_response.get("deviceId"):
        h["X-Device-ID"] = token_response["deviceId"]
    return h


@pytest.fixture
def analyst(client) -> dict:
    return auth(login(client, ANALYST))


@pytest.fixture
def admin(client) -> dict:
    return auth(login(client, ADMIN))


@pytest.fixture
def phone(client) -> dict:
    """The operator signed in on the seeded handset D-01."""
    t = login(client, OPERATOR, {"installId": "demo-install-galaxy-s10", "name": "Asad's Galaxy S10",
                                 "model": "Samsung SM-G975F", "os": "Android 12", "appVersion": "0.9.4 (212)"})
    assert t["deviceId"] == "D-01"
    return auth(t, device=True)


@pytest.fixture
def operator(client) -> dict:
    return auth(login(client, OPERATOR))


def pytest_sessionfinish(session, exitstatus):  # noqa: ARG001
    reset_engine()
    shutil.rmtree(_TMP, ignore_errors=True)
