"""Reference data: products (label data), species, quadrats and the pickers' vocabularies."""

from __future__ import annotations

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import Conflict, NotFound
from app.domain.enums import (
    APPLICATION_MODES, CAPTURE_METHODS, DOSE_UNITS, GRID_ACTUATOR, GROWTH_STAGES, HRAC_MOA, ROLE_LABEL,
    SEVERITY_META, SURVEY_ROLE_META, WEED_CLASS_META, ZONE_STATE_LABEL, HracGroup, SurveyStatus,
)
from app.models import Field, Product, Quadrat, Species, User
from app.schemas.observations import QuadratCreate, QuadratOut
from app.schemas.system import ProductIn, ProductOut, ProductPatch, SpeciesOut
from app.services import access, ids, journal


def product_out(p: Product) -> ProductOut:
    g = HracGroup(p.hrac)
    return ProductOut(id=p.id, trade=p.trade, active=p.active, hrac=g, hrac_display=g.display, target=p.target,  # type: ignore[arg-type]
                      crop=p.crop, formulation=p.formulation, registered=p.registered, updated_at=p.updated_at)


def list_products(db: Session, *, target: str | None = None, hrac: str | None = None, crop: str | None = None,
                  q: str | None = None, include_unregistered: bool = False) -> list[Product]:
    stmt = select(Product)
    if not include_unregistered:
        stmt = stmt.where(Product.registered.is_(True))
    if target:
        stmt = stmt.where(Product.target == target)
    if hrac:
        stmt = stmt.where(Product.hrac == hrac)
    if crop:
        stmt = stmt.where(Product.crop == crop)
    if q:
        like = f"%{q.strip()}%"
        stmt = stmt.where(or_(Product.trade.ilike(like), Product.active.ilike(like)))
    return list(db.scalars(stmt.order_by(Product.trade)).all())


def create_product(db: Session, user: User, data: ProductIn) -> Product:
    access.require_decider(user, "Product label data")
    if db.scalar(select(Product).where(func.lower(Product.trade) == data.trade.strip().lower())):
        raise Conflict(f"{data.trade} is already on the label list")
    p = Product(id=ids.next_id(db, "product", Product), **{**data.model_dump(), "trade": data.trade.strip()})
    db.add(p)
    journal.audit(db, user, "Product added", f"{p.trade} · {HracGroup(p.hrac).display}", entity="product", entity_id=p.id)
    journal.change(db, entity="product", op="create", entity_id=p.id, user=user, owned_by_mobile=False, summary=f"{p.trade} added to label list")
    db.flush()
    return p


def patch_product(db: Session, user: User, product_id: str, data: ProductPatch) -> Product:
    access.require_decider(user, "Product label data")
    p = db.get(Product, product_id)
    if p is None:
        raise NotFound(f"Product {product_id} not found")
    for k, v in data.model_dump(exclude_unset=True).items():
        if v is not None:
            setattr(p, k, v)
    p.updated_at = clock.now()
    journal.audit(db, user, "Product updated", p.trade, entity="product", entity_id=p.id)
    journal.change(db, entity="product", op="update", entity_id=p.id, user=user, owned_by_mobile=False, summary=f"{p.trade} label data updated")
    return p


def species_out(s: Species) -> SpeciesOut:
    return SpeciesOut(latin=s.latin, local=s.local, common=s.common, cls=s.cls, note=s.note)  # type: ignore[arg-type]


def list_species(db: Session) -> list[Species]:
    return list(db.scalars(select(Species).order_by(Species.cls.desc(), Species.latin)).all())


def reference() -> dict:
    """Every picker's vocabulary in one response, so clients never hard-code it."""
    return {
        "weedClasses": [{"value": k.value, **v} for k, v in WEED_CLASS_META.items()],
        "severities": [{"value": k.value, **v} for k, v in SEVERITY_META.items()],
        "severityBands": {"cleanBelowPct": 10, "moderateUpToPct": 30},
        "surveyRoles": [{"value": k.value, **v} for k, v in SURVEY_ROLE_META.items()],
        "surveyStatuses": [s.value for s in SurveyStatus],
        "zoneStates": [{"value": k.value, "label": v} for k, v in ZONE_STATE_LABEL.items()],
        "gridSizes": [{"meters": k, "label": f"{k} m", "actuator": v} for k, v in GRID_ACTUATOR.items()],
        "hracGroups": [{"value": g.value, "code": g.code, "moa": HRAC_MOA[g], "display": g.display} for g in HracGroup],
        "applicationModes": list(APPLICATION_MODES),
        "growthStages": list(GROWTH_STAGES),
        "doseUnits": list(DOSE_UNITS),
        "captureMethods": list(CAPTURE_METHODS),
        "roles": [{"value": k.value, "label": v} for k, v in ROLE_LABEL.items()],
        "acceptableControlPct": 70,
        "units": {"acreSqm": 4046.86, "kanalPerAcre": 8, "marlaPerKanal": 20, "hectaresPerAcre": 0.404686},
    }


# ----------------------------------------------------------------------------- quadrats


def quadrat_out(q: Quadrat, field_name: str) -> QuadratOut:
    counts = [(str(a), int(b)) for a, b in (q.species_counts or [])]
    return QuadratOut(id=q.id, frame_id=q.frame_id, recorded_at=q.recorded_at, field_id=q.field_id, field_name=field_name,
                      zone_label=q.zone_label, species_counts=counts, total=sum(c for _, c in counts),
                      verified_by=q.verified_by, seed=q.seed)


def list_quadrats(db: Session, user: User, field_id: str | None = None) -> list[Quadrat]:
    stmt = select(Quadrat).join(Field, Field.id == Quadrat.field_id).where(Field.archived_at.is_(None))
    scope = access.field_scope(user)
    if scope is not None:
        stmt = stmt.where(Quadrat.field_id.in_(scope or {"__none__"}))
    if field_id:
        stmt = stmt.where(Quadrat.field_id == field_id)
    return list(db.scalars(stmt.order_by(Quadrat.recorded_at.desc())).all())


def create_quadrat(db: Session, user: User, data: QuadratCreate, *, device_id: str | None = None,
                   client_seq: int | None = None) -> tuple[Quadrat, bool]:
    if data.client_id:
        existing = db.scalar(select(Quadrat).where(Quadrat.client_id == data.client_id))
        if existing is not None:
            return existing, False
    f = access.get_field(db, user, data.field_id)
    q = Quadrat(
        id=ids.next_id(db, "quadrat", Quadrat), client_id=data.client_id, frame_id=data.frame_id.strip(),
        recorded_at=data.recorded_at or clock.now(), field_id=f.id, zone_label=data.zone_label,
        species_counts=[[a, b] for a, b in data.species_counts], verified_by=data.verified_by, recorded_by=user.id,
        seed=data.seed,
    )
    db.add(q)
    db.flush()
    journal.change(db, entity="quadrat", op="create", entity_id=q.id, user=user, device_id=device_id, client_seq=client_seq,
                   at=q.recorded_at, summary=f"Quadrat {q.id} · {q.frame_id} counted", payload=data.model_dump(mode="json"))
    return q, True
