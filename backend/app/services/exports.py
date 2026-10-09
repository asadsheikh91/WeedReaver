"""Prescription exports for other tools (spec section 43.2).

GeoJSON (RFC 7946), ESRI Shapefile (zipped .shp/.shx/.dbf/.prj) and ISO 11783-10 TASKDATA.
Every format carries geometry only: application rates are never written, because this system
records applications and never prescribes a rate.
"""

from __future__ import annotations

import io
import json
import re
import zipfile
from dataclasses import dataclass
from xml.etree import ElementTree as ET

import shapefile  # pyshp
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import __version__
from app.analysis.raster import Grid, cell_ref
from app.core import clock
from app.core.errors import NotFound
from app.domain import geo
from app.domain.enums import CLASS_CODES, ExportFormat, SurveyRole
from app.models import ExportJob, Field, User
from app.schemas.system import ExportCreate, ExportInclude, ExportOut, ExportPreview
from app.services import access, analysis, ids, journal
from app.services.station import get_station
from app.services.storage import get_storage

WGS84_PRJ = (
    'GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],'
    'UNIT["degree",0.0174532925199433]]'
)
NOTE = "Zone and cell geometry only. This system publishes no application rates."


@dataclass
class ZoneShape:
    letter: str
    severity: str
    dominant_class: str
    area_sqm: int
    mean_infest_pct: float
    route_order: int
    polygons: list  # [[exterior, *holes], ...] local metres


@dataclass
class ExportData:
    field: Field
    grid: Grid
    zones: list[ZoneShape]
    threshold: float
    org: str
    season: str


def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "field"


def _thr(t: float) -> str:
    return f"{t:g}"


def filename_for(f: Field, fmt: ExportFormat, grid_m: int, threshold: float) -> str:
    base = f"{_slug(f.name)}_prescription_{grid_m}m_t{_thr(threshold)}"
    return {ExportFormat.GEOJSON: base + ".geojson", ExportFormat.SHAPEFILE: base + ".zip",
            ExportFormat.TASKDATA: base + "_TASKDATA.xml"}[fmt]


def gather(db: Session, f: Field, grid_m: int, threshold: float) -> ExportData:
    ctx, survey, surface = analysis.require_pre(db, f, SurveyRole.PRE)
    station = get_station(db)
    grid = analysis.grid(surface, grid_m, threshold)
    if abs(threshold - station.threshold_pct) < 1e-9:
        rows = analysis.active_zones(db, ctx.season)
        zones = [ZoneShape(z.letter, z.severity, z.dominant_class, z.area_sqm, z.mean_infest_pct, z.route_order, z.geometry)
                 for z in rows]
    else:
        computed = analysis.zones(surface, analysis.gate_of(f), threshold, analysis.prior_zones(db, ctx.season))
        zones = [ZoneShape(z.letter, z.severity.value, z.dominant_class.value, z.area_sqm, z.mean_infest_pct, z.route_order,
                           [[[list(p) for p in ring] for ring in poly] for poly in z.polygons]) for z in computed]
    return ExportData(field=f, grid=grid, zones=zones, threshold=threshold, org=station.org_name, season=station.season_label)


# ----------------------------------------------------------------------------- geometry helpers


def _lonlat(f: Field, p) -> tuple[float, float]:
    lat, lon = geo.to_latlon(f.lat, f.lon, (float(p[0]), float(p[1])))
    return round(lon, 8), round(lat, 8)


def _ring_ll(f: Field, ring, *, ccw: bool) -> list[tuple[float, float]]:
    pts = [_lonlat(f, p) for p in ring]
    if (geo.signed_area(pts) > 0) != ccw:  # in (lon, lat) a positive signed area is counter-clockwise
        pts.reverse()
    return pts + [pts[0]]


def _cell_ring(g: Grid, c: int, r: int):
    x, y, s = g.origin_x + c * g.cell_m, g.origin_y + r * g.cell_m, g.cell_m
    return [(x, y), (x + s, y), (x + s, y + s), (x, y + s)]


def _cells(g: Grid, inc: ExportInclude):
    rows, cols = g.treated.shape
    for r in range(rows):
        for c in range(cols):
            if not g.inside[r, c]:
                continue
            if g.treated[r, c] or (inc.abstained and g.abstained[r, c]):
                yield c, r


def boundary_feature(f: Field) -> dict:
    return {
        "type": "Feature",
        "properties": {"id": f.id, "name": f.name, "village": f.village, "areaSqm": round(f.area_sqm, 2)},
        "geometry": {"type": "Polygon", "coordinates": [_ring_ll(f, f.boundary, ccw=True)]},
    }


# ----------------------------------------------------------------------------- GeoJSON


def build_geojson(d: ExportData, inc: ExportInclude) -> bytes:
    f, g = d.field, d.grid
    features: list[dict] = []
    if inc.boundary:
        features.append({
            "type": "Feature",
            "properties": {"kind": "boundary", "id": f.id, "name": f.name, "village": f.village,
                           "area_sqm": round(f.area_sqm, 1), "area_acres": round(geo.acres(f.area_sqm), 3)},
            "geometry": {"type": "Polygon", "coordinates": [_ring_ll(f, f.boundary, ccw=True)]},
        })
    if inc.zones:
        for z in d.zones:
            polys = [[_ring_ll(f, poly[0], ccw=True)] + [_ring_ll(f, h, ccw=False) for h in poly[1:]] for poly in z.polygons]
            features.append({
                "type": "Feature",
                "properties": {"kind": "zone", "label": f"Zone {z.letter}", "severity": z.severity.lower(),
                               "class": z.dominant_class.lower(), "area_sqm": z.area_sqm,
                               "mean_cover_pct": round(z.mean_infest_pct, 1), "route_order": z.route_order},
                "geometry": {"type": "MultiPolygon", "coordinates": polys},
            })
    if inc.cells:
        for c, r in _cells(g, inc):
            features.append({
                "type": "Feature",
                "properties": {"kind": "abstained" if g.abstained[r, c] else "spray", "ref": cell_ref(c, r),
                               "cover_pct": round(float(g.infest_pct[r, c]), 1),
                               "class": CLASS_CODES[int(g.class_idx[r, c])].value.lower(),
                               "confidence": round(float(g.confidence[r, c]), 2)},
                "geometry": {"type": "Polygon", "coordinates": [_ring_ll(f, _cell_ring(g, c, r), ccw=True)]},
            })
    doc = {
        "type": "FeatureCollection",
        "name": f"{f.name} prescription",
        "properties": {"generator": f"WeedReaver {__version__}", "field_id": f.id, "season": d.season,
                       "threshold_pct": d.threshold, "grid_m": g.cell_m, "generated_at": clock.now().isoformat(), "note": NOTE},
        "features": features,
    }
    return json.dumps(doc, separators=(",", ":")).encode()


# ----------------------------------------------------------------------------- Shapefile


def build_shapefile(d: ExportData, inc: ExportInclude) -> bytes:
    f, g = d.field, d.grid
    out = io.BytesIO()
    base = filename_for(f, ExportFormat.SHAPEFILE, g.cell_m, d.threshold)[:-4]

    def layer(name: str, fields: list[tuple], shapes: list[tuple[list, list]]) -> None:
        shp, shx, dbf = io.BytesIO(), io.BytesIO(), io.BytesIO()
        w = shapefile.Writer(shp=shp, shx=shx, dbf=dbf, shapeType=shapefile.POLYGON)
        for fd in fields:
            w.field(*fd)
        for parts, record in shapes:
            w.poly(parts)
            w.record(*record)
        w.close()
        for ext, buf in (("shp", shp), ("shx", shx), ("dbf", dbf)):
            zf.writestr(f"{base}_{name}.{ext}", buf.getvalue())
        zf.writestr(f"{base}_{name}.prj", WGS84_PRJ)
        zf.writestr(f"{base}_{name}.cpg", "UTF-8")

    # Shapefile rings: exterior clockwise, holes counter-clockwise.
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        if inc.boundary:
            layer("boundary", [("FIELD_ID", "C", 16), ("NAME", "C", 80), ("AREA_SQM", "N", 14, 1)],
                  [([_ring_ll(f, f.boundary, ccw=False)], [f.id, f.name, round(f.area_sqm, 1)])])
        if inc.zones and d.zones:
            shapes = []
            for z in d.zones:
                parts = []
                for poly in z.polygons:
                    parts.append(_ring_ll(f, poly[0], ccw=False))
                    parts += [_ring_ll(f, h, ccw=True) for h in poly[1:]]
                shapes.append((parts, [f"Zone {z.letter}", z.severity.lower(), z.dominant_class.lower(), z.area_sqm,
                                       round(z.mean_infest_pct, 1), z.route_order]))
            layer("zones", [("LABEL", "C", 12), ("SEVERITY", "C", 10), ("CLASS", "C", 10), ("AREA_SQM", "N", 10, 0),
                            ("MEAN_PCT", "N", 6, 1), ("ROUTE", "N", 4, 0)], shapes)
        if inc.cells:
            shapes = [([_ring_ll(f, _cell_ring(g, c, r), ccw=False)],
                       [cell_ref(c, r), "abstained" if g.abstained[r, c] else "spray", round(float(g.infest_pct[r, c]), 1),
                        round(float(g.confidence[r, c]), 2)]) for c, r in _cells(g, inc)]
            if shapes:
                layer("cells", [("REF", "C", 10), ("KIND", "C", 10), ("COVER_PCT", "N", 6, 1), ("CONF", "N", 5, 2)], shapes)
        zf.writestr("README.txt", f"{f.name} ({f.id}) prescription, {d.season}\nThreshold {_thr(d.threshold)}%, grid {g.cell_m} m.\n"
                                  f"Coordinates: WGS84 longitude/latitude.\n{NOTE}\n")
    return out.getvalue()


# ----------------------------------------------------------------------------- ISO 11783-10


def build_taskdata(d: ExportData) -> bytes:
    f = d.field

    def pln(parent: ET.Element, polygon_type: str, rings: list[tuple[list, str]], designator: str | None = None) -> None:
        el = ET.SubElement(parent, "PLN", {"A": polygon_type, **({"B": designator} if designator else {})})
        for ring, lsg_type in rings:
            lsg = ET.SubElement(el, "LSG", {"A": lsg_type})
            for lon, lat in ring:
                ET.SubElement(lsg, "PNT", {"A": "2", "C": f"{lat:.9f}", "D": f"{lon:.9f}"})

    root = ET.Element("ISO11783_TaskData", {
        "VersionMajor": "4", "VersionMinor": "2", "ManagementSoftwareManufacturer": "WeedReaver",
        "ManagementSoftwareVersion": __version__, "DataTransferOrigin": "1",
    })
    root.append(ET.Comment(f" Treatment-zone shapes only. {NOTE} Process-data values are deliberately absent. "))
    ET.SubElement(root, "CTR", {"A": "CTR1", "B": d.org[:32]})
    ET.SubElement(root, "FRM", {"A": "FRM1", "B": f.village[:32], "I": "CTR1"})
    pfd = ET.SubElement(root, "PFD", {"A": "PFD1", "C": f.name[:32], "D": str(int(round(f.area_sqm))), "E": "CTR1", "F": "FRM1"})
    pln(pfd, "1", [(_ring_ll(f, f.boundary, ccw=True), "1")], "Boundary")
    tsk = ET.SubElement(root, "TSK", {"A": "TSK1", "B": f"{f.name} spot spray, {d.season}"[:32], "C": "CTR1", "D": "FRM1",
                                      "E": "PFD1", "G": "1"})
    tsk.append(ET.Comment(f" threshold {_thr(d.threshold)}% · grid {d.grid.cell_m} m · {len(d.zones)} zones · {d.grid.flagged} cells "))
    for i, z in enumerate(d.zones, start=1):
        tzn = ET.SubElement(tsk, "TZN", {"A": str(min(i, 254)), "B": f"Zone {z.letter}", "C": str(min(i, 254))})
        for poly in z.polygons:
            rings = [(_ring_ll(f, poly[0], ccw=True), "1")] + [(_ring_ll(f, h, ccw=False), "2") for h in poly[1:]]
            pln(tzn, "2", rings)
    ET.indent(root, space="  ")
    return b'<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="utf-8")


# ----------------------------------------------------------------------------- jobs


def build(db: Session, f: Field, fmt: ExportFormat, grid_m: int, threshold: float, inc: ExportInclude) -> tuple[bytes, ExportData]:
    d = gather(db, f, grid_m, threshold)
    if fmt == ExportFormat.GEOJSON:
        return build_geojson(d, inc), d
    if fmt == ExportFormat.SHAPEFILE:
        return build_shapefile(d, inc), d
    return build_taskdata(d), d


def _cells_counted(d: ExportData, fmt: ExportFormat, inc: ExportInclude) -> int:
    if fmt == ExportFormat.TASKDATA or not inc.cells:
        return 0
    return d.grid.flagged + (d.grid.abstained_count if inc.abstained else 0)


def export_out(j: ExportJob, field_name: str, api_prefix: str, names: dict[str, str] | None = None) -> ExportOut:
    return ExportOut(
        id=j.id, field_id=j.field_id, field_name=field_name, format=ExportFormat(j.format), grid_size=j.grid_size,
        threshold_pct=j.threshold_pct, include=j.include or {}, size_bytes=j.size_bytes, size_kb=max(1, round(j.size_bytes / 1024)),
        cells=j.cells, zones=j.zones, filename=j.filename, at=j.at,
        created_by=(names or {}).get(j.created_by or "", j.created_by), download_url=f"{api_prefix}/exports/{j.id}/download",
    )


def create(db: Session, user: User, data: ExportCreate) -> ExportJob:
    f = access.get_field(db, user, data.field_id)
    station = get_station(db)
    grid_m = analysis.check_grid(data.grid_size or station.default_grid_m)
    threshold = analysis.check_threshold(data.threshold_pct if data.threshold_pct is not None else station.threshold_pct)
    inc = data.include if data.format != ExportFormat.TASKDATA else ExportInclude(boundary=True, zones=True, cells=False, abstained=False)
    body, d = build(db, f, data.format, grid_m, threshold, inc)
    job_id = ids.next_id(db, "export", ExportJob)
    name = filename_for(f, data.format, grid_m, threshold)
    key = f"exports/{job_id}/{name}"
    get_storage().save_bytes(key, body)
    job = ExportJob(
        id=job_id, field_id=f.id, format=data.format.value, grid_size=grid_m, threshold_pct=threshold,
        include=inc.model_dump(by_alias=True), size_bytes=len(body), cells=_cells_counted(d, data.format, inc),
        zones=len(d.zones), filename=name, storage_key=key, created_by=user.id, at=clock.now(),
    )
    db.add(job)
    journal.audit(db, user, "Export generated", f"{data.format.value} · {f.name} · {grid_m} m · {_thr(threshold)}%",
                  entity="export", entity_id=job_id)
    return job


def preview(db: Session, user: User, data: ExportCreate, max_lines: int = 30) -> ExportPreview:
    f = access.get_field(db, user, data.field_id)
    station = get_station(db)
    grid_m = analysis.check_grid(data.grid_size or station.default_grid_m)
    threshold = analysis.check_threshold(data.threshold_pct if data.threshold_pct is not None else station.threshold_pct)
    inc = data.include if data.format != ExportFormat.TASKDATA else ExportInclude(boundary=True, zones=True, cells=False, abstained=False)
    body, d = build(db, f, data.format, grid_m, threshold, inc)
    if data.format == ExportFormat.SHAPEFILE:
        with zipfile.ZipFile(io.BytesIO(body)) as zf:
            text = "\n".join(f"{i.filename}  {i.file_size:,} bytes" for i in zf.infolist())
    elif data.format == ExportFormat.GEOJSON:
        text = json.dumps(json.loads(body), indent=1)
    else:
        text = body.decode()
    lines = text.split("\n")
    return ExportPreview(filename=filename_for(f, data.format, grid_m, threshold), format=data.format, size_bytes=len(body),
                         cells=_cells_counted(d, data.format, inc), zones=len(d.zones), lines=len(lines),
                         preview="\n".join(lines[:max_lines]))


def get_job(db: Session, user: User, export_id: str) -> ExportJob:
    j = db.get(ExportJob, export_id)
    if j is None or not access.can_see_field(user, j.field_id):
        raise NotFound(f"Export {export_id} not found")
    return j


def file_for(db: Session, user: User, j: ExportJob) -> bytes:
    """The stored file, rebuilt from its recorded settings if storage was cleared."""
    storage = get_storage()
    if j.storage_key and storage.exists(j.storage_key):
        return storage.open(j.storage_key).read_bytes()
    f = access.get_field(db, user, j.field_id, include_archived=True)
    inc = ExportInclude.model_validate(j.include or {})
    body, _ = build(db, f, ExportFormat(j.format), j.grid_size, j.threshold_pct, inc)
    key = j.storage_key or f"exports/{j.id}/{j.filename}"
    storage.save_bytes(key, body)
    j.storage_key, j.size_bytes = key, len(body)
    return body


def list_jobs(db: Session, user: User, field_id: str | None = None) -> list[ExportJob]:
    q = select(ExportJob).join(Field, Field.id == ExportJob.field_id)
    scope = access.field_scope(user)
    if scope is not None:
        q = q.where(ExportJob.field_id.in_(scope or {"__none__"}))
    if field_id:
        q = q.where(ExportJob.field_id == field_id)
    return list(db.scalars(q.order_by(ExportJob.at.desc())).all())


MEDIA_TYPES = {ExportFormat.GEOJSON: "application/geo+json", ExportFormat.SHAPEFILE: "application/zip",
               ExportFormat.TASKDATA: "application/xml"}
