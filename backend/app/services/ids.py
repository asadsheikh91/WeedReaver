"""Human-readable ids in the grammar both clients use: F-047, S-01, SC-041, T-0118, CL-12 ...

Backed by a counters table incremented with UPDATE ... RETURNING, which takes a row lock on
PostgreSQL, so concurrent requests never hand out the same id.
"""

from __future__ import annotations

from sqlalchemy import insert, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import Counter

#: name -> (format, first value handed out minus one)
SEQUENCES: dict[str, tuple[str, int]] = {
    "field": ("F-{:03d}", 900),
    "survey": ("S-{:02d}", 6),
    "scan": ("SC-{:03d}", 40),
    "treatment": ("T-{:04d}", 117),
    "verification": ("V-{:03d}", 0),
    "quadrat": ("Q-{:02d}", 3),
    "changelog": ("CL-{}", 8),
    "seq": ("{}", 219),
    "audit": ("A-{}", 9),
    "export": ("X-{:02d}", 3),
    "device": ("D-{:02d}", 2),
    "user": ("U-{}", 4),
    "product": ("P-{:02d}", 11),
    "operator": ("OP-{:04d}", 212),
}


def _bump(db: Session, name: str) -> int:
    stmt = update(Counter).where(Counter.name == name).values(value=Counter.value + 1).returning(Counter.value)
    value = db.execute(stmt).scalar_one_or_none()
    if value is not None:
        return int(value)
    start = SEQUENCES[name][1]
    try:
        with db.begin_nested():
            db.execute(insert(Counter).values(name=name, value=start + 1))
        return start + 1
    except IntegrityError:  # another transaction created it first
        return int(db.execute(stmt).scalar_one())


def next_value(db: Session, name: str) -> int:
    return _bump(db, name)


def next_id(db: Session, name: str, model: type | None = None) -> str:
    """Next id for `name`; when `model` is given, skips any value already taken."""
    fmt = SEQUENCES[name][0]
    while True:
        candidate = fmt.format(_bump(db, name))
        if model is None or db.execute(select(model).where(model.id == candidate)).first() is None:  # type: ignore[attr-defined]
            return candidate


def ensure_counters(db: Session, floors: dict[str, int] | None = None) -> None:
    """Create missing counters; raise any counter below a floor (e.g. after importing data)."""
    floors = floors or {}
    for name, (_, start) in SEQUENCES.items():
        row = db.get(Counter, name)
        floor = max(start, floors.get(name, start))
        if row is None:
            db.add(Counter(name=name, value=floor))
        elif row.value < floor:
            row.value = floor
    db.flush()
