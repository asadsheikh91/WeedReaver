"""From uploaded photos to an orthomosaic on the map.

GPS is read from every photo at upload; a flight that cannot be georeferenced, or that was
uploaded against the wrong field, is refused before photogrammetry; uploads can be retried and
resumed; the finished mosaic is served as Web Mercator tiles to whoever may see the field.
"""

from __future__ import annotations

import io
import math
import zipfile

import morecantile
import pytest
from pyodm.types import TaskStatus

from app.pipeline import exif, orthotiles
from app.pipeline.runner import run_pending
from tests.conftest import API
from tests.imaging import JPEG_NO_GPS, exif_tiff, flight_jpegs, jpeg_with_gps, write_orthophoto
from tests.test_odm import FIELD_LAT, FIELD_LON, FakeNode, _flight_with_images, odm  # noqa: F401 - fixture

TMS = morecantile.tms.get("WebMercatorQuad")


# ----------------------------------------------------------------------------- EXIF GPS


@pytest.mark.parametrize("bo", ["<", ">"])
@pytest.mark.parametrize("lat,lon", [(31.8942, 73.2711), (-33.8688, -151.2093), (51.5, -0.1276), (-1.29, 36.82)])
def test_exif_gps_round_trip(bo, lat, lon):
    fix = exif.gps_from_jpeg(jpeg_with_gps(lat, lon, alt=87.25, byteorder=bo))
    assert fix is not None
    assert fix.lat == pytest.approx(lat, abs=1e-6) and fix.lon == pytest.approx(lon, abs=1e-6) and fix.alt_m == 87.25


def test_exif_gps_absent_or_broken_is_none():
    assert exif.gps_from_jpeg(JPEG_NO_GPS) is None
    good = jpeg_with_gps(31.9, 73.27)
    for cut in (4, 20, 40, 60, len(good) // 2, len(good) - 10):
        exif.gps_from_jpeg(good[:cut])  # never raises on a truncated file
    assert exif.gps_from_jpeg(b"not a jpeg at all") is None
    assert exif.gps_from_tiff(b"II*\x00\xff\xff\xff\xff") is None  # IFD offset beyond the data
    assert exif.gps_from_jpeg(jpeg_with_gps(0.0, 0.0)) is None  # (0, 0): the camera had no fix
    # GPS IFD pointer present but pointing nowhere
    t = bytearray(exif_tiff(31.9, 73.27))
    t[18:22] = (60000).to_bytes(4, "little")
    assert exif.gps_from_tiff(bytes(t)) is None


def test_exif_gps_from_a_file(tmp_path):
    p = tmp_path / "DJI_0001.JPG"
    p.write_bytes(jpeg_with_gps(31.9, 73.27))
    assert exif.read_gps(p) == exif.GpsFix(31.9, 73.27, 120.5)
    assert exif.read_gps(tmp_path / "missing.jpg") is None


# ----------------------------------------------------------------------------- upload


def _field(client, analyst, name="Upload field", lat=FIELD_LAT, lon=FIELD_LON):
    body = {"clientId": f"up-{name}", "name": name, "captureMethod": "Drawn",
            "boundary": [{"x": 0, "y": 0}, {"x": 140, "y": 0}, {"x": 140, "y": 100}, {"x": 0, "y": 100}],
            "lat": lat, "lon": lon}
    r = client.post(f"{API}/fields", json=body, headers=analyst)
    assert r.status_code == 201, r.text
    fid = r.json()["id"]
    sid = client.post(f"{API}/surveys/uploads", json={"fieldId": fid, "role": "PRE"}, headers=analyst).json()["id"]
    return fid, sid


def _send(client, headers, sid, named: list[tuple[str, bytes]]):
    files = [("files", (n, b, "image/jpeg")) for n, b in named]
    return client.post(f"{API}/surveys/{sid}/images", files=files, headers=headers)


def test_upload_records_gps_and_is_idempotent(client, analyst):
    _, sid = _field(client, analyst)
    shots = flight_jpegs(FIELD_LAT - 0.0003, FIELD_LON + 0.0002, n=6)
    first = _send(client, analyst, sid, shots[:4]).json()
    assert (first["received"], first["duplicates"], first["images"], first["withGps"]) == (4, 0, 4, 4)
    # The browser lost the response and sends the batch again, then the rest: nothing doubles.
    again = _send(client, analyst, sid, shots).json()
    assert (again["received"], again["duplicates"], again["images"], again["withGps"]) == (2, 4, 6, 6)
    assert again["imageBytes"] == sum(len(b) for _, b in shots)
    listing = client.get(f"{API}/surveys/{sid}/images", headers=analyst).json()
    assert listing["images"] == 6 and listing["withGps"] == 6
    assert sorted(f["filename"] for f in listing["files"]) == sorted(n for n, _ in shots)
    assert all(f["hasGps"] for f in listing["files"])
    assert listing["offsetFromFieldM"] == 0.0  # the camera was over the field
    assert listing["gpsCentre"]["lat"] == pytest.approx(FIELD_LAT - 0.0003, abs=1e-6)


def test_zip_uploads_are_read_and_deduplicated(client, analyst):
    _, sid = _field(client, analyst)
    shots = flight_jpegs(FIELD_LAT - 0.0003, FIELD_LON + 0.0002, n=5)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for n, b in shots:
            zf.writestr(f"DCIM/100MEDIA/{n}", b)
        zf.writestr("__MACOSX/DCIM/._DJI_0000.JPG", b"\x00\x05\x16\x07")
    files = [("files", ("card.zip", buf.getvalue(), "application/zip"))]
    up = client.post(f"{API}/surveys/{sid}/images", files=files, headers=analyst).json()
    assert (up["received"], up["rejected"], up["withGps"]) == (5, [], 5)
    up = client.post(f"{API}/surveys/{sid}/images", files=files, headers=analyst).json()
    assert (up["received"], up["duplicates"], up["images"]) == (0, 5, 5)


def test_clear_images_starts_the_upload_again(client, analyst, operator):
    _, sid = _field(client, analyst)
    _send(client, analyst, sid, flight_jpegs(FIELD_LAT, FIELD_LON, n=3))
    assert client.delete(f"{API}/surveys/{sid}/images", headers=operator).status_code == 403
    assert client.delete(f"{API}/surveys/{sid}/images", headers=analyst).status_code == 200
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert (s["images"], s["imageBytes"]) == (0, 0)
    assert client.get(f"{API}/surveys/{sid}/images", headers=analyst).json()["files"] == []


def test_only_deciders_upload(client, analyst, operator, trainee):
    _, sid = _field(client, analyst)
    for who in (operator, trainee):
        assert _send(client, who, sid, flight_jpegs(FIELD_LAT, FIELD_LON, n=1)).status_code in (403, 404)
        assert client.post(f"{API}/surveys/{sid}/process", json={}, headers=who).status_code in (403, 404)


# ----------------------------------------------------------------------------- refused before photogrammetry


def test_flight_checks_before_photogrammetry(client, analyst, odm):  # noqa: F811
    odm(FakeNode([(TaskStatus.COMPLETED, 100)]))
    _, sid = _field(client, analyst)

    _send(client, analyst, sid, flight_jpegs(FIELD_LAT, FIELD_LON, n=3))
    r = client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst)
    assert r.status_code == 422 and "at least 5" in r.json()["error"]["message"]

    _send(client, analyst, sid, [(f"EDIT_{k}.JPG", JPEG_NO_GPS + bytes([k])) for k in range(5)])
    r = client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst)
    err = r.json()["error"]
    assert r.status_code == 422 and err["message"].startswith("5 of 8 images carry no GPS position")
    assert err["details"] == {"images": 8, "withGps": 3}

    # Photos from about 3.3 km east of the field: uploaded against the wrong one.
    client.delete(f"{API}/surveys/{sid}/images", headers=analyst)
    _send(client, analyst, sid, flight_jpegs(FIELD_LAT, FIELD_LON + 0.035, n=6))
    r = client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst)
    assert r.status_code == 422 and "km from Upload field" in r.json()["error"]["message"]
    assert client.get(f"{API}/surveys/{sid}", headers=analyst).json()["status"] == "SCHEDULED"  # nothing was queued

    client.delete(f"{API}/surveys/{sid}/images", headers=analyst)
    _send(client, analyst, sid, flight_jpegs(FIELD_LAT - 0.0003, FIELD_LON + 0.0002, n=6))
    assert client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst).status_code == 202


def test_simulated_engine_needs_no_gps(client, analyst):
    """The simulator draws no picture: demonstrations and tests may upload anything."""
    _, sid = _field(client, analyst)
    _send(client, analyst, sid, [("a.jpg", JPEG_NO_GPS)])
    assert client.post(f"{API}/surveys/{sid}/process", json={}, headers=analyst).status_code == 202


# ----------------------------------------------------------------------------- on the map


def _tile_at(info: dict, lat: float, lon: float, z: int | None = None):
    return TMS.tile(lon, lat, z if z is not None else info["maxZoom"])


def test_orthomosaic_on_the_map(client, analyst, trainee, odm):  # noqa: F811
    odm(FakeNode([(TaskStatus.RUNNING, 50), (TaskStatus.COMPLETED, 100)]))
    fid, sid = _flight_with_images(client, analyst)
    run_pending()
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert s["status"] == "READY", s["error"]

    r = client.get(f"{API}/surveys/{sid}/orthomosaic/info", headers=analyst)
    assert r.status_code == 200, r.text
    info = r.json()
    b = info["bounds"]
    # The fake node's mosaic has its north-west corner on the field's anchor and spans 160 x 120 m. It is
    # a rectangle in UTM, turned slightly against lat/lon (grid convergence), so its envelope reaches a
    # few metres north of that corner.
    assert b["west"] == pytest.approx(FIELD_LON, abs=1e-7)
    assert FIELD_LAT <= b["north"] < FIELD_LAT + 5 / 111_320
    width_m = (b["east"] - b["west"]) * 111_320 * math.cos(math.radians(FIELD_LAT))
    assert 160 <= width_m < 163  # 160 cos(c) + 120 sin(c) for the convergence c (under 1 degree here)
    assert info["fieldId"] == fid and info["epsg"] == 32643 and info["gsdCm"] == 25.0
    assert 0 < info["minZoom"] <= info["maxZoom"] <= 24
    assert info["tileUrl"] == f"/surveys/{sid}/orthomosaic/tiles/{{z}}/{{x}}/{{y}}.webp?v={info['version']}"

    centre = _tile_at(info, (b["north"] + b["south"]) / 2, (b["east"] + b["west"]) / 2)
    r = client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/{centre.z}/{centre.x}/{centre.y}.webp", headers=analyst)
    assert r.status_code == 200 and r.headers["content-type"] == "image/webp"
    assert r.content[:4] == b"RIFF" and r.content[8:12] == b"WEBP"
    assert "max-age" in r.headers["cache-control"] and "private" in r.headers["cache-control"]
    png = client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/{centre.z}/{centre.x}/{centre.y}.png", headers=analyst)
    assert png.status_code == 200 and png.content[:8] == b"\x89PNG\r\n\x1a\n"
    low = _tile_at(info, b["north"], b["west"], z=max(0, info["minZoom"] - 2))
    assert client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/{low.z}/{low.x}/{low.y}.webp", headers=analyst).status_code == 200

    away = _tile_at(info, FIELD_LAT + 0.05, FIELD_LON)
    r = client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/{away.z}/{away.x}/{away.y}.webp", headers=analyst)
    assert r.status_code == 204 and r.content == b""
    assert client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/3/8/0.webp", headers=analyst).status_code == 404
    assert client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/30/0/0.webp", headers=analyst).status_code == 422
    assert client.get(f"{API}/surveys/{sid}/orthomosaic/tiles/18/1/1.gif", headers=analyst).status_code == 422

    # Visibility follows the field: a trainee assigned elsewhere does not learn the flight exists.
    assert client.get(f"{API}/surveys/{sid}/orthomosaic/info", headers=trainee).status_code == 404
    url = f"{API}/surveys/{sid}/orthomosaic/tiles/{centre.z}/{centre.x}/{centre.y}.webp"
    assert client.get(url, headers=trainee).status_code == 404
    assert client.get(url).status_code == 401


def test_flight_without_orthomosaic(client, analyst):
    # S-01 was processed by the simulator: zones, but no picture.
    r = client.get(f"{API}/surveys/S-01/orthomosaic/info", headers=analyst)
    assert r.status_code == 404
    assert client.get(f"{API}/surveys/S-01/orthomosaic/tiles/18/184428/106546.webp", headers=analyst).status_code == 404


def test_unreadable_orthophoto_fails_the_flight_and_is_not_kept(client, analyst, odm):  # noqa: F811
    from app.services.storage import get_storage

    odm(FakeNode([(TaskStatus.COMPLETED, 100)], header_only=True))
    _, sid = _flight_with_images(client, analyst)
    run_pending()
    s = client.get(f"{API}/surveys/{sid}", headers=analyst).json()
    assert s["status"] == "FAILED" and s["error"].startswith("The orthomosaic cannot be shown on a map")
    assert not s["hasOrthomosaic"]
    work = get_storage().path(f"flights/{sid}/work/odm/odm_orthophoto/odm_orthophoto.tif")
    assert not work.exists()  # a retry stitches again rather than reusing the bad file


# ----------------------------------------------------------------------------- tile rendering


def _rgba(png: bytes):
    """Decode a PNG tile into a (4, 256, 256) array."""
    from rasterio.io import MemoryFile

    with MemoryFile(png) as mem, mem.open() as ds:
        return ds.read()


def test_tiles_are_transparent_where_nothing_was_stitched(tmp_path):
    p = write_orthophoto(tmp_path / "o.tif", FIELD_LAT, FIELD_LON, gsd_m=0.05)
    info = orthotiles.describe(p)
    # The tile holding the mosaic's north-west corner straddles its 8 m transparent margin.
    t = TMS.tile(info.west + 6e-5, info.north - 6e-5, info.max_zoom - 2)
    a = _rgba(orthotiles.render_tile(p, t.z, t.x, t.y, "png"))
    assert a.shape == (4, 256, 256)
    assert (a[3] == 0).any() and (a[3] == 255).any()
    c = TMS.tile((info.west + info.east) / 2, (info.north + info.south) / 2, info.max_zoom)
    a = _rgba(orthotiles.render_tile(p, c.z, c.x, c.y, "png"))
    assert (a[3] == 255).all()
    # Wholly inside the margin: nothing to draw, so no tile at all.
    t = TMS.tile(info.west + 1e-6, info.north - 1e-6, info.max_zoom)
    assert orthotiles.render_tile(p, t.z, t.x, t.y, "webp") is None


def test_sixteen_bit_mosaics_are_stretched(tmp_path):
    p = write_orthophoto(tmp_path / "o16.tif", FIELD_LAT, FIELD_LON, dtype="uint16", alpha=False)
    info = orthotiles.describe(p)
    assert info.dtype == "uint16" and info.indexes == (1, 2, 3) and info.rescale is not None
    c = TMS.tile((info.west + info.east) / 2, (info.north + info.south) / 2, info.max_zoom)
    assert orthotiles.render_tile(p, c.z, c.x, c.y, "webp")[:4] == b"RIFF"


def test_describe_refuses_what_it_cannot_place(tmp_path):
    from tests.test_odm import write_geotiff

    header_only = tmp_path / "h.tif"
    write_geotiff(header_only)
    with pytest.raises(orthotiles.OrthomosaicUnreadable):
        orthotiles.describe(header_only)
    with pytest.raises(orthotiles.OrthomosaicUnreadable):
        orthotiles.describe(tmp_path / "missing.tif")
    jpg = tmp_path / "x.tif"
    jpg.write_bytes(JPEG_NO_GPS)
    with pytest.raises(orthotiles.OrthomosaicUnreadable):
        orthotiles.describe(jpg)
