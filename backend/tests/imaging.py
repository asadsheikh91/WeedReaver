"""Test fixtures that look like what a drone and OpenDroneMap produce.

`jpeg_with_gps` builds a (tiny) JPEG whose EXIF block carries a GPS position the way DJI cameras
write it. `write_orthophoto` writes a real raster GeoTIFF in UTM, RGBA with an alpha band and
overviews: the shape of ODM's odm_orthophoto.tif.
"""

from __future__ import annotations

import struct
from pathlib import Path


def _rational(value: float, den: int = 10_000) -> tuple[int, int]:
    return int(round(value * den)), den


def _dms(deg: float) -> list[tuple[int, int]]:
    deg = abs(deg)
    d = int(deg)
    m = int((deg - d) * 60)
    s = (deg - d - m / 60) * 3600
    return [(d, 1), (m, 1), _rational(s)]


def exif_tiff(lat: float, lon: float, alt: float | None = 120.5, *, byteorder: str = "<") -> bytes:
    """The TIFF structure inside a JPEG's APP1 "Exif" segment, with only a GPS IFD."""
    bo = byteorder
    tags: list[tuple[int, int, int, bytes]] = [  # (tag, type, count, payload)
        (1, 2, 2, (b"N" if lat >= 0 else b"S") + b"\x00"),
        (2, 5, 3, b"".join(struct.pack(bo + "II", *r) for r in _dms(lat))),
        (3, 2, 2, (b"E" if lon >= 0 else b"W") + b"\x00"),
        (4, 5, 3, b"".join(struct.pack(bo + "II", *r) for r in _dms(lon))),
    ]
    if alt is not None:
        tags += [(5, 1, 1, bytes([0 if alt >= 0 else 1])), (6, 5, 1, struct.pack(bo + "II", *_rational(abs(alt), 100)))]
    ifd0_at = 8
    gps_at = ifd0_at + 2 + 12 + 4
    data_at = gps_at + 2 + 12 * len(tags) + 4
    out = bytearray((b"II" if bo == "<" else b"MM") + struct.pack(bo + "HI", 42, ifd0_at))
    out += struct.pack(bo + "H", 1) + struct.pack(bo + "HHII", 0x8825, 4, 1, gps_at) + struct.pack(bo + "I", 0)
    out += struct.pack(bo + "H", len(tags))
    blobs = bytearray()
    for tag, typ, count, payload in tags:
        if len(payload) <= 4:
            field = payload.ljust(4, b"\x00")
        else:
            field = struct.pack(bo + "I", data_at + len(blobs))
            blobs += payload
        out += struct.pack(bo + "HHI", tag, typ, count) + field
    out += struct.pack(bo + "I", 0) + blobs
    return bytes(out)


def jpeg_with_gps(lat: float, lon: float, alt: float | None = 120.5, *, byteorder: str = "<", pad: int = 0) -> bytes:
    """A JPEG with a JFIF header, then the EXIF segment (as cameras write it). `pad` varies the size."""
    jfif = b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    app0 = b"\xff\xe0" + struct.pack(">H", len(jfif) + 2) + jfif
    body = b"Exif\x00\x00" + exif_tiff(lat, lon, alt, byteorder=byteorder)
    app1 = b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body
    return b"\xff\xd8" + app0 + app1 + b"\xff\xd9" + b"\x00" * pad


JPEG_NO_GPS = b"\xff\xd8\xff\xe0" + b"\x00" * 64 + b"\xff\xd9"


def flight_jpegs(lat: float, lon: float, n: int = 6, spacing_m: float = 20.0) -> list[tuple[str, bytes]]:
    """`n` images on a short east-west line starting at (lat, lon), like one pass of a mapping flight."""
    import math

    m_per_deg_lon = 111_320.0 * math.cos(math.radians(lat))
    return [(f"DJI_{k:04d}.JPG", jpeg_with_gps(lat, lon + k * spacing_m / m_per_deg_lon, pad=k)) for k in range(n)]


def write_orthophoto(path: Path, lat: float, lon: float, *, width_m: float = 160.0, height_m: float = 120.0,
                     gsd_m: float = 0.25, epsg: int = 32643, dtype: str = "uint8", alpha: bool = True) -> Path:
    """A real raster GeoTIFF whose north-west corner is at (lat, lon), with a transparent margin."""
    import numpy as np
    import rasterio
    from rasterio.enums import ColorInterp, Resampling
    from rasterio.transform import from_origin
    from rasterio.warp import transform as warp

    xs, ys = warp("EPSG:4326", f"EPSG:{epsg}", [lon], [lat])
    w, h = int(width_m / gsd_m), int(height_m / gsd_m)
    yy, xx = np.mgrid[0:h, 0:w]
    top = 255 if dtype == "uint8" else 4000
    bands = [(xx * top // max(1, w - 1)), (yy * top // max(1, h - 1)), np.full((h, w), top // 2)]
    interp = [ColorInterp.red, ColorInterp.green, ColorInterp.blue]
    if alpha:
        edge = int(8 / gsd_m)  # 8 m of "nothing stitched here" around the mosaic
        inside = (xx >= edge) & (yy >= edge) & (xx < w - edge) & (yy < h - edge)
        bands.append(np.where(inside, top, 0))
        interp.append(ColorInterp.alpha)
    path.parent.mkdir(parents=True, exist_ok=True)
    profile = dict(driver="GTiff", width=w, height=h, count=len(bands), dtype=dtype, crs=f"EPSG:{epsg}",
                   transform=from_origin(xs[0], ys[0], gsd_m, gsd_m), tiled=True, blockxsize=256, blockysize=256,
                   compress="deflate")
    with rasterio.open(path, "w", **profile) as dst:
        for k, band in enumerate(bands, start=1):
            dst.write(band.astype(dtype), k)
        dst.colorinterp = interp
        dst.build_overviews([2, 4, 8], Resampling.average)
    return path
