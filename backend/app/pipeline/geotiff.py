"""Reads the georeferencing of a GeoTIFF from its header: size, pixel size, origin and CRS.

Enough to check an orthophoto and to map its pixels into a field's frame, without GDAL. Handles
classic TIFF and BigTIFF (ODM writes BigTIFF for large mosaics), either byte order. Pixel data
is never read, so a multi-gigabyte mosaic is described in microseconds.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass
from pathlib import Path

# TIFF field types -> (struct code, size in bytes)
_TYPES = {1: ("B", 1), 2: ("c", 1), 3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 6: ("b", 1), 7: ("B", 1),
          8: ("h", 2), 9: ("i", 4), 10: ("ii", 8), 11: ("f", 4), 12: ("d", 8), 16: ("Q", 8), 17: ("q", 8), 18: ("Q", 8)}

T_WIDTH, T_HEIGHT, T_COMPRESSION, T_SAMPLES = 256, 257, 259, 277
T_PIXEL_SCALE, T_TIEPOINT, T_GEOKEYS = 33550, 33922, 34735
K_MODEL_TYPE, K_GEOGRAPHIC, K_PROJECTED = 1024, 2048, 3072
_WANTED = {T_WIDTH, T_HEIGHT, T_COMPRESSION, T_SAMPLES, T_PIXEL_SCALE, T_TIEPOINT, T_GEOKEYS}
_COMPRESSION = {1: "none", 5: "LZW", 7: "JPEG", 8: "DEFLATE", 32773: "PACKBITS", 32946: "DEFLATE", 34887: "LERC",
                34925: "LZMA", 50000: "ZSTD", 50001: "WEBP"}


@dataclass
class GeoTiffInfo:
    width: int
    height: int
    bands: int
    #: Pixel size in CRS units (metres for a projected CRS such as UTM, degrees for EPSG:4326).
    pixel_x: float
    pixel_y: float
    #: CRS coordinates of the top-left corner of the top-left pixel.
    origin_x: float
    origin_y: float
    epsg: int | None
    geographic: bool
    compression: str

    def gsd_cm(self, lat: float | None = None) -> float:
        """Ground sample distance in cm/px (for a geographic CRS, at latitude `lat`)."""
        if not self.geographic:
            return self.pixel_x * 100
        metres = self.pixel_x * 111_320.0 * math.cos(math.radians(lat or 0.0))
        return metres * 100

    def as_meta(self) -> dict:
        return {"width": self.width, "height": self.height, "bands": self.bands, "pixelX": self.pixel_x,
                "pixelY": self.pixel_y, "originX": self.origin_x, "originY": self.origin_y, "epsg": self.epsg,
                "geographic": self.geographic, "compression": self.compression}


def describe(path: Path) -> GeoTiffInfo:
    """Raises ValueError if the file is not a georeferenced TIFF."""
    with open(path, "rb") as f:
        head = f.read(16)
        if head[:2] == b"II":
            bo = "<"
        elif head[:2] == b"MM":
            bo = ">"
        else:
            raise ValueError("not a TIFF file")
        magic = struct.unpack(bo + "H", head[2:4])[0]
        if magic == 42:
            big = False
            ifd = struct.unpack(bo + "I", head[4:8])[0]
        elif magic == 43:
            big = True
            ifd = struct.unpack(bo + "Q", head[8:16])[0]
        else:
            raise ValueError("not a TIFF file")
        tags = _read_ifd(f, bo, big, ifd)

    try:
        width, height = int(tags[T_WIDTH][0]), int(tags[T_HEIGHT][0])
        scale, tie = tags[T_PIXEL_SCALE], tags[T_TIEPOINT]
    except KeyError as exc:
        raise ValueError("the TIFF has no georeferencing (pixel scale and tie point)") from exc
    keys = _geokeys(tags.get(T_GEOKEYS, ()))
    geographic = keys.get(K_MODEL_TYPE) == 2 or (K_PROJECTED not in keys and K_GEOGRAPHIC in keys)
    epsg = keys.get(K_GEOGRAPHIC if geographic else K_PROJECTED)
    i, j, x, y = tie[0], tie[1], tie[3], tie[4]
    return GeoTiffInfo(
        width=width, height=height, bands=int(tags.get(T_SAMPLES, (1,))[0]),
        pixel_x=float(scale[0]), pixel_y=float(scale[1]),
        origin_x=float(x - i * scale[0]), origin_y=float(y + j * scale[1]),
        epsg=int(epsg) if epsg and epsg != 32767 else None, geographic=geographic,
        compression=_COMPRESSION.get(int(tags.get(T_COMPRESSION, (1,))[0]), str(tags.get(T_COMPRESSION, ("?",))[0])),
    )


def _read_ifd(f, bo: str, big: bool, offset: int) -> dict[int, tuple]:
    f.seek(offset)
    if big:
        n = struct.unpack(bo + "Q", f.read(8))[0]
        entry, inline = 20, 8
    else:
        n = struct.unpack(bo + "H", f.read(2))[0]
        entry, inline = 12, 4
    raw = f.read(n * entry)
    out: dict[int, tuple] = {}
    for k in range(n):
        e = raw[k * entry:(k + 1) * entry]
        if big:
            tag, typ, count = struct.unpack(bo + "HHQ", e[:12])
            field = e[12:20]
        else:
            tag, typ, count = struct.unpack(bo + "HHI", e[:8])
            field = e[8:12]
        if tag not in _WANTED or typ not in _TYPES:
            continue
        code, size = _TYPES[typ]
        nbytes = size * count
        if nbytes <= inline:
            data = field[:nbytes]
        else:
            pos = struct.unpack(bo + ("Q" if big else "I"), field)[0]
            here = f.tell()
            f.seek(pos)
            data = f.read(nbytes)
            f.seek(here)
        values = struct.unpack(bo + code * count, data)
        if typ in (5, 10):  # rationals come as numerator, denominator pairs
            values = tuple(values[a] / values[a + 1] if values[a + 1] else 0.0 for a in range(0, len(values), 2))
        out[tag] = values
    return out


def _geokeys(directory: tuple) -> dict[int, int]:
    """GeoKeyDirectory: a 4-short header, then (key, location, count, value) per key."""
    if len(directory) < 4:
        return {}
    keys: dict[int, int] = {}
    for k in range(int(directory[3])):
        base = 4 + 4 * k
        if base + 3 >= len(directory):
            break
        key, location, _count, value = directory[base:base + 4]
        if location == 0:  # value stored inline; doubles and strings live in other tags
            keys[int(key)] = int(value)
    return keys
