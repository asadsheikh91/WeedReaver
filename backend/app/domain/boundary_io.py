"""Reading a parcel boundary out of KML, GeoJSON or CSV (spec section 36.4).

Returns one outer ring in WGS84. When a file holds several polygons, the largest is taken:
station plot registers often carry a few small outbuildings next to the parcel.
"""

from __future__ import annotations

import csv
import io
import json
import xml.etree.ElementTree as ET
from dataclasses import dataclass

from app.core.errors import Invalid
from app.domain import geo


@dataclass
class ParsedBoundary:
    ring: list[tuple[float, float]]  # (lat, lon)
    source_format: str
    name: str | None = None


def _approx_area(ring: list[tuple[float, float]]) -> float:
    if len(ring) < 3:
        return 0.0
    lat0 = max(p[0] for p in ring)
    lon0 = min(p[1] for p in ring)
    return geo.area([geo.from_latlon(lat0, lon0, lat, lon) for lat, lon in ring])


def _validate_pair(lat: float, lon: float) -> tuple[float, float]:
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise Invalid("Coordinates out of range; expected WGS84 longitude, latitude")
    return lat, lon


def _from_geojson(doc: object) -> tuple[list[list[tuple[float, float]]], str | None]:
    rings: list[list[tuple[float, float]]] = []
    name: str | None = None

    def ring_of(coords: list) -> list[tuple[float, float]]:
        return [_validate_pair(float(c[1]), float(c[0])) for c in coords if isinstance(c, (list, tuple)) and len(c) >= 2]

    def walk(obj: object) -> None:
        nonlocal name
        if not isinstance(obj, dict):
            return
        t = obj.get("type")
        if t == "FeatureCollection":
            for f in obj.get("features", []) or []:
                walk(f)
        elif t == "Feature":
            props = obj.get("properties") or {}
            if name is None and isinstance(props, dict):
                n = props.get("name") or props.get("Name")
                name = str(n)[:80] if n else None
            walk(obj.get("geometry"))
        elif t == "Polygon":
            coords = obj.get("coordinates") or []
            if coords:
                rings.append(ring_of(coords[0]))
        elif t == "MultiPolygon":
            for poly in obj.get("coordinates") or []:
                if poly:
                    rings.append(ring_of(poly[0]))
        elif t == "GeometryCollection":
            for g in obj.get("geometries", []) or []:
                walk(g)

    walk(doc)
    return rings, name


def _from_kml(text: str) -> tuple[list[list[tuple[float, float]]], str | None]:
    head = text[:4096].upper()
    if "<!DOCTYPE" in head or "<!ENTITY" in text.upper():
        raise Invalid("KML with a DOCTYPE or entities is not accepted")
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise Invalid(f"Not valid KML: {exc}") from exc
    rings: list[list[tuple[float, float]]] = []
    name: str | None = None
    for el in root.iter():
        tag = el.tag.rsplit("}", 1)[-1]
        if tag == "name" and name is None and el.text and el.text.strip():
            name = el.text.strip()[:80]
        if tag == "outerBoundaryIs":
            for c in el.iter():
                if c.tag.rsplit("}", 1)[-1] == "coordinates" and c.text:
                    ring = []
                    for tok in c.text.split():
                        parts = tok.split(",")
                        if len(parts) >= 2:
                            ring.append(_validate_pair(float(parts[1]), float(parts[0])))
                    rings.append(ring)
    if not rings:  # a bare LinearRing / LineString of coordinates
        for c in root.iter():
            if c.tag.rsplit("}", 1)[-1] == "coordinates" and c.text:
                ring = []
                for tok in c.text.split():
                    parts = tok.split(",")
                    if len(parts) >= 2:
                        ring.append(_validate_pair(float(parts[1]), float(parts[0])))
                rings.append(ring)
    # Placemark names are more useful than the document's.
    for pm in root.iter():
        if pm.tag.rsplit("}", 1)[-1] == "Placemark":
            for c in pm:
                if c.tag.rsplit("}", 1)[-1] == "name" and c.text and c.text.strip():
                    return rings, c.text.strip()[:80]
    return rings, name


def _from_csv(text: str) -> list[tuple[float, float]]:
    rows = list(csv.reader(io.StringIO(text)))
    rows = [r for r in rows if any(cell.strip() for cell in r)]
    if not rows:
        return []
    header = [h.strip().lower() for h in rows[0]]
    lat_i = lon_i = None
    for i, h in enumerate(header):
        if h in ("lat", "latitude", "y"):
            lat_i = i
        elif h in ("lon", "lng", "long", "longitude", "x"):
            lon_i = i
    body = rows[1:] if lat_i is not None and lon_i is not None else rows
    if lat_i is None or lon_i is None:
        lat_i, lon_i = 0, 1  # headerless: lat, lon
    ring = []
    for r in body:
        try:
            ring.append(_validate_pair(float(r[lat_i]), float(r[lon_i])))
        except (ValueError, IndexError) as exc:
            raise Invalid("CSV rows must hold numeric latitude and longitude") from exc
    return ring


def parse_boundary(content: bytes, filename: str = "") -> ParsedBoundary:
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise Invalid("Boundary file must be UTF-8 text (KML, GeoJSON or CSV)") from exc
    stripped = text.lstrip()
    lower = filename.lower()
    name = None
    if lower.endswith((".geojson", ".json")) or stripped.startswith("{"):
        try:
            rings, name = _from_geojson(json.loads(text))
        except json.JSONDecodeError as exc:
            raise Invalid(f"Not valid GeoJSON: {exc.msg}") from exc
        fmt = "GeoJSON"
    elif lower.endswith(".kml") or stripped.startswith("<"):
        rings, name = _from_kml(text)
        fmt = "KML"
    elif lower.endswith(".csv") or "," in stripped.split("\n", 1)[0]:
        rings = [_from_csv(text)]
        fmt = "CSV"
    else:
        raise Invalid("Unrecognised boundary file; use KML, GeoJSON or CSV")

    rings = [r for r in rings if len(r) >= 3]
    if not rings:
        raise Invalid("No polygon found. Use a file with one closed ring.")
    ring = max(rings, key=_approx_area)
    if len(ring) > 1 and ring[0] == ring[-1]:
        ring = ring[:-1]
    if len(ring) < 3:
        raise Invalid("A boundary needs at least three distinct vertices")
    return ParsedBoundary(ring=ring, source_format=fmt, name=name)
