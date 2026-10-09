from __future__ import annotations

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Query, Response, status
from sqlalchemy import select

from app.api.deps import CurrentUser, DbDep, OptionalDevice, PagingDep
from app.domain.enums import HracGroup, WeedClass
from app.models import Field
from app.schemas.analysis import RotationCheckIn, RotationCheckOut
from app.schemas.common import Page
from app.schemas.observations import QuadratCreate, QuadratOut, TreatmentCreate, TreatmentOut, TreatmentResult
from app.schemas.system import ProductIn, ProductOut, ProductPatch
from app.services import access, catalog, treatments

router = APIRouter(tags=["treatments"])


@router.get("/treatments", response_model=Page[TreatmentOut], summary="Treatment records, newest first")
def list_treatments(
    user: CurrentUser, db: DbDep, paging: PagingDep,
    field_id: Annotated[str | None, Query(alias="fieldId")] = None,
    hrac: HracGroup | None = None,
    date_from: Annotated[datetime | None, Query(alias="from")] = None,
    date_to: Annotated[datetime | None, Query(alias="to")] = None,
    season: Annotated[str | None, Query(description='Agricultural year, e.g. "2025-26"')] = None,
    q: Annotated[str | None, Query(max_length=80)] = None,
) -> Page[TreatmentOut]:
    rows, total = treatments.list_treatments(db, user, field_id=field_id, hrac=hrac, date_from=date_from, date_to=date_to,
                                             season=season, q=q, limit=paging.limit, offset=paging.offset)
    names = {f.id: f.name for f in db.scalars(select(Field)).all()}
    return Page(items=[treatments.treatment_out(t, names.get(t.field_id)) for t in rows], total=total,
                limit=paging.limit, offset=paging.offset)


@router.post("/treatments", response_model=TreatmentResult, status_code=status.HTTP_201_CREATED,
             summary="Record what was applied. The dose is stored exactly as written. Idempotent on clientId")
def create_treatment(body: TreatmentCreate, user: CurrentUser, db: DbDep, device: OptionalDevice,
                     response: Response) -> TreatmentResult:
    out, created = treatments.create_treatment(db, user, body, device_id=device.id if device else None)
    db.commit()
    if not created:
        response.status_code = status.HTTP_200_OK
    return out


@router.get("/treatments/{treatment_id}", response_model=TreatmentOut)
def get_treatment(treatment_id: str, user: CurrentUser, db: DbDep) -> TreatmentOut:
    t = treatments.get_treatment(db, user, treatment_id)
    return treatments.treatment_out(t, db.get(Field, t.field_id).name)


@router.post("/rotation/check", response_model=RotationCheckOut,
             summary="Before a record is written: is this the same mode of action as last time?")
def rotation_check(body: RotationCheckIn, user: CurrentUser, db: DbDep) -> RotationCheckOut:
    f = access.get_field(db, user, body.field_id)
    return treatments.rotation_check(db, f, body.product_id, body.product)


# ----------------------------------------------------------------------------- products


@router.get("/products", response_model=list[ProductOut], summary="Herbicide label list")
def list_products(
    user: CurrentUser, db: DbDep, target: WeedClass | None = None, hrac: HracGroup | None = None,
    crop: str | None = None, q: Annotated[str | None, Query(max_length=80)] = None,
    include_unregistered: Annotated[bool, Query(alias="includeUnregistered")] = False,
) -> list[ProductOut]:
    rows = catalog.list_products(db, target=target.value if target else None, hrac=hrac.value if hrac else None, crop=crop,
                                 q=q, include_unregistered=include_unregistered)
    return [catalog.product_out(p) for p in rows]


@router.post("/products", response_model=ProductOut, status_code=status.HTTP_201_CREATED)
def create_product(body: ProductIn, user: CurrentUser, db: DbDep) -> ProductOut:
    p = catalog.create_product(db, user, body)
    db.commit()
    return catalog.product_out(p)


@router.patch("/products/{product_id}", response_model=ProductOut)
def patch_product(product_id: str, body: ProductPatch, user: CurrentUser, db: DbDep) -> ProductOut:
    p = catalog.patch_product(db, user, product_id, body)
    db.commit()
    return catalog.product_out(p)


# ----------------------------------------------------------------------------- quadrats


@router.get("/quadrats", response_model=list[QuadratOut], summary="Ground-truth counts in fiducial frames")
def list_quadrats(user: CurrentUser, db: DbDep, field_id: Annotated[str | None, Query(alias="fieldId")] = None) -> list[QuadratOut]:
    names = {f.id: f.name for f in db.scalars(select(Field)).all()}
    return [catalog.quadrat_out(q, names.get(q.field_id, q.field_id)) for q in catalog.list_quadrats(db, user, field_id)]


@router.post("/quadrats", response_model=QuadratOut, status_code=status.HTTP_201_CREATED)
def create_quadrat(body: QuadratCreate, user: CurrentUser, db: DbDep, device: OptionalDevice, response: Response) -> QuadratOut:
    q, created = catalog.create_quadrat(db, user, body, device_id=device.id if device else None)
    db.commit()
    if not created:
        response.status_code = status.HTTP_200_OK
    return catalog.quadrat_out(q, db.get(Field, q.field_id).name)
