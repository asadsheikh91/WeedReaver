from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, File, Query, Response, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy import select

from app.api.deps import CurrentUser, DbDep, OptionalDevice, PagingDep
from app.core.errors import NotFound
from app.domain.enums import ScanStatus
from app.models import LeafScan, User
from app.schemas.common import Page
from app.schemas.observations import AnnotateIn, ResolveIn, ScanCreate, ScanOut
from app.schemas.system import SpeciesOut
from app.services import access, catalog, insight, scans
from app.services.storage import get_storage

router = APIRouter(tags=["scans & review"])


def _names(db) -> dict[str, str]:
    return {u.id: u.name for u in db.scalars(select(User)).all()}


@router.get("/scans", response_model=Page[ScanOut], summary="Leaf scans, newest first")
def list_scans(
    user: CurrentUser, db: DbDep, paging: PagingDep,
    field_id: Annotated[str | None, Query(alias="fieldId")] = None,
    status_: Annotated[ScanStatus | None, Query(alias="status")] = None,
    device_id: Annotated[str | None, Query(alias="deviceId")] = None,
    zone: Annotated[str | None, Query(description='Zone label, e.g. "Zone A"')] = None,
) -> Page[ScanOut]:
    rows, total = scans.list_scans(db, user, field_id=field_id, status=status_, device_id=device_id, zone_label=zone,
                                   limit=paging.limit, offset=paging.offset)
    names = _names(db)
    return Page(items=[scans.scan_out(db, s, names) for s in rows], total=total, limit=paging.limit, offset=paging.offset)


@router.post("/scans", response_model=ScanOut, status_code=status.HTTP_201_CREATED,
             summary="Record a scan classified on the phone. Idempotent on clientId")
def create_scan(body: ScanCreate, user: CurrentUser, db: DbDep, device: OptionalDevice, response: Response) -> ScanOut:
    s, created = scans.create_scan(db, user, body, device_id=device.id if device else None)
    db.commit()
    if not created:
        response.status_code = status.HTTP_200_OK
    return scans.scan_out(db, s)


@router.get("/scans/{scan_id}", response_model=ScanOut)
def get_scan(scan_id: str, user: CurrentUser, db: DbDep) -> ScanOut:
    return scans.scan_out(db, scans.get_scan(db, user, scan_id))


@router.put("/scans/{scan_id}/photo", response_model=ScanOut, summary="Attach the captured leaf photo (JPEG/PNG/WebP)")
def put_photo(scan_id: str, user: CurrentUser, db: DbDep, file: Annotated[UploadFile, File()]) -> ScanOut:
    s = scans.attach_photo(db, user, scans.get_scan(db, user, scan_id), file)
    db.commit()
    return scans.scan_out(db, s)


@router.get("/scans/{scan_id}/photo", summary="The leaf photo")
def get_photo(scan_id: str, user: CurrentUser, db: DbDep) -> FileResponse:
    s = scans.get_scan(db, user, scan_id)
    if not s.photo_key:
        raise NotFound("This scan has no photo")
    return FileResponse(get_storage().open(s.photo_key), media_type=s.photo_content_type or "image/jpeg",
                        headers={"Cache-Control": "private, max-age=86400"})


@router.post("/scans/{scan_id}/annotation", response_model=ScanOut, summary="Operator's suggested label, from the field")
def annotate(scan_id: str, body: AnnotateIn, user: CurrentUser, db: DbDep, device: OptionalDevice) -> ScanOut:
    s = scans.annotate(db, user, scans.get_scan(db, user, scan_id), body, device_id=device.id if device else None)
    db.commit()
    return scans.scan_out(db, s)


@router.post("/scans/{scan_id}/resolve", response_model=ScanOut, summary="Analyst's label for an abstained scan")
def resolve(scan_id: str, body: ResolveIn, user: CurrentUser, db: DbDep) -> ScanOut:
    s = scans.resolve(db, user, scans.get_scan(db, user, scan_id), body)
    db.commit()
    return scans.scan_out(db, s)


@router.post("/scans/{scan_id}/reopen", response_model=ScanOut, summary="Undo a resolution")
def reopen(scan_id: str, user: CurrentUser, db: DbDep) -> ScanOut:
    s = scans.reopen(db, user, scans.get_scan(db, user, scan_id))
    db.commit()
    return scans.scan_out(db, s)


@router.get("/species", response_model=list[SpeciesOut], summary="The leaf scanner's label set")
def species(user: CurrentUser, db: DbDep) -> list[SpeciesOut]:
    return [catalog.species_out(s) for s in catalog.list_species(db)]


@router.get("/review/summary", summary="Counts for the review queue badge")
def review_summary(user: CurrentUser, db: DbDep) -> dict:
    scope = access.field_scope(user)
    rows = [s for s in db.scalars(select(LeafScan).where(LeafScan.abstained.is_(True)))
            if scope is None or s.field_id in scope]
    cells = insight.abstained_cells(db, user)
    return {
        "scansNeedingLabel": sum(1 for s in rows if not s.resolved and not s.annotation),
        "scansAnnotated": sum(1 for s in rows if not s.resolved and s.annotation),
        "scansWaiting": sum(1 for s in rows if not s.resolved),
        "scansResolved": sum(1 for s in rows if s.resolved),
        "cellsAbstained": sum(c["count"] for c in cells),
        "cellsByField": [{"fieldId": c["fieldId"], "fieldName": c["fieldName"], "count": c["count"]} for c in cells],
    }


@router.get("/review/cells", summary="Grid cells the aerial model abstained on: send a person to look")
def review_cells(user: CurrentUser, db: DbDep, field_id: Annotated[str | None, Query(alias="fieldId")] = None,
                 size: Annotated[int | None, Query()] = None) -> list[dict]:
    return insight.abstained_cells(db, user, field_id=field_id, size=size)
