from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Query

from app.api.deps import CurrentUser, DbDep
from app.schemas.system import ConditionsOut, StationOut, StationPatch, ThresholdIn, ThresholdOut
from app.services import access, catalog, definitions, weather
from app.services.station import get_station, station_out

router = APIRouter(tags=["settings"])


@router.get("/settings", response_model=StationOut, summary="Station definitions both surfaces read")
def get_settings_(user: CurrentUser, db: DbDep) -> StationOut:
    return station_out(get_station(db))


@router.patch("/settings", response_model=StationOut)
def patch_settings(body: StationPatch, user: CurrentUser, db: DbDep) -> StationOut:
    row = definitions.patch_station(db, user, body)
    db.commit()
    return station_out(row)


@router.put("/settings/threshold", response_model=ThresholdOut,
            summary="Publish the prescription threshold (re-zones every surveyed field)")
def publish_threshold(body: ThresholdIn, user: CurrentUser, db: DbDep) -> ThresholdOut:
    out = definitions.publish_threshold(db, user, body.threshold_pct)
    db.commit()
    return out


@router.get("/reference", summary="Vocabularies for every picker: classes, HRAC groups, units, modes")
def reference(user: CurrentUser) -> dict:
    return catalog.reference()


@router.get("/conditions", response_model=ConditionsOut, summary="Spray-window conditions at a field")
def conditions(user: CurrentUser, db: DbDep, field_id: Annotated[str | None, Query(alias="fieldId")] = None) -> ConditionsOut:
    if field_id:
        f = access.get_field(db, user, field_id)
        return weather.conditions(f.lat, f.lon)
    first = db.scalars(access.visible_fields_query(user)).first()
    return weather.conditions(first.lat if first else 31.8942, first.lon if first else 73.2711)
