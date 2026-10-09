"""Operations commands: `python -m app.cli <command>`.

    migrate                      apply database migrations
    bootstrap                    migrate, then station settings, label list and species (production start)
    seed-demo [--reset]          load the demonstration dataset (development / demos)
    create-admin --email --name  create an administrator (password prompted)
    run-jobs                     process queued flights in the foreground, then exit
"""

from __future__ import annotations

import argparse
import getpass
import os
import sys
from pathlib import Path

from app.core.config import get_settings
from app.core.logging import configure_logging

ROOT = Path(__file__).resolve().parent.parent


def migrate() -> None:
    from alembic import command
    from alembic.config import Config

    import logging

    logging.getLogger("alembic.runtime.plugins").setLevel(logging.WARNING)
    cfg = Config(str(ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(ROOT / "alembic"))
    command.upgrade(cfg, "head")
    print("Database is at the latest migration.")


def bootstrap() -> None:
    migrate()
    from sqlalchemy import select

    from app.db.session import session_scope
    from app.models import Product, Species
    from app.seed import demo
    from app.services import ids
    from app.services.station import get_station

    with session_scope() as db:
        get_station(db)
        if db.scalars(select(Species.latin)).first() is None:
            for latin, local, common, cls, note in demo.SPECIES:
                db.add(Species(latin=latin, local=local, common=common, cls=cls.value, note=note))
        if db.scalars(select(Product.id)).first() is None:
            for i, (trade, active, hrac, target, crop, form) in enumerate(demo.PRODUCTS, start=1):
                db.add(Product(id=f"P-{i:02d}", trade=trade, active=active, hrac=hrac, target=target.value, crop=crop, formulation=form))
        ids.ensure_counters(db)
    print("Station settings, label list and species are in place.")


def seed_demo(reset: bool, password: str | None) -> None:
    s = get_settings()
    if s.environment == "production":
        sys.exit("Refusing to load demonstration data in production.")
    from app.db.base import Base
    from app.db.session import get_engine, session_scope
    from app.seed import demo

    if reset:
        import app.models  # noqa: F401

        Base.metadata.drop_all(get_engine())
        from sqlalchemy import text

        with get_engine().begin() as conn:
            conn.execute(text("DROP TABLE IF EXISTS alembic_version"))
    migrate()
    pw = password or os.environ.get("WR_DEMO_PASSWORD") or "weedreaver-demo"
    with session_scope() as db:
        if not demo.is_empty(db):
            sys.exit("The database already has data. Use --reset to replace it with the demonstration dataset.")
        demo.seed(db, pw)
    print("Demonstration data loaded. Accounts (password: %s):" % ("<from WR_DEMO_PASSWORD>" if password is None and os.environ.get("WR_DEMO_PASSWORD") else pw))
    for _, name, role, email, *_ in demo.USERS:
        print(f"  {role.value:<9} {email:<42} {name}")


def create_admin(email: str, name: str, password: str | None) -> None:
    from app.core.security import MIN_PASSWORD_LENGTH, hash_password
    from app.db.session import session_scope
    from app.domain.enums import Role
    from app.models import User
    from app.schemas.auth import Preferences
    from app.services import ids
    from app.services.users import get_by_email

    pw = password or getpass.getpass("Password: ")
    if len(pw) < MIN_PASSWORD_LENGTH:
        sys.exit(f"Password must be at least {MIN_PASSWORD_LENGTH} characters.")
    with session_scope() as db:
        if get_by_email(db, email):
            sys.exit(f"{email} already exists.")
        db.add(User(id=ids.next_id(db, "user", User), email=email.strip().lower(), name=name.strip(), role=Role.ADMIN.value,
                    password_hash=hash_password(pw), scope_all=True, preferences=Preferences().model_dump()))
    print(f"Administrator {email} created.")


def run_jobs() -> None:
    from app.pipeline.runner import recover_stale, run_pending

    recover_stale()
    n = run_pending()
    print(f"Processed {n} job(s).")


def main(argv: list[str] | None = None) -> None:
    s = get_settings()
    configure_logging(s.log_level, s.log_json)
    p = argparse.ArgumentParser(prog="python -m app.cli")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("migrate")
    sub.add_parser("bootstrap")
    sd = sub.add_parser("seed-demo")
    sd.add_argument("--reset", action="store_true", help="drop every table first")
    sd.add_argument("--password", help="password for every demo account (default: WR_DEMO_PASSWORD or weedreaver-demo)")
    ca = sub.add_parser("create-admin")
    ca.add_argument("--email", required=True)
    ca.add_argument("--name", required=True)
    ca.add_argument("--password", help="omit to be prompted")
    sub.add_parser("run-jobs")
    a = p.parse_args(argv)
    if a.cmd == "migrate":
        migrate()
    elif a.cmd == "bootstrap":
        bootstrap()
    elif a.cmd == "seed-demo":
        seed_demo(a.reset, a.password)
    elif a.cmd == "create-admin":
        create_admin(a.email, a.name, a.password)
    elif a.cmd == "run-jobs":
        run_jobs()


if __name__ == "__main__":
    main()
