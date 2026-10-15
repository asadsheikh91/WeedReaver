"""Reads the GPS position a drone camera writes into each photo's EXIF block.

OpenDroneMap georeferences an orthomosaic from these positions; a flight without them stitches
into a picture that cannot be placed on a map. Reading them at upload time lets the station
refuse such a flight in seconds instead of after an hour of photogrammetry, and notice when a
flight was uploaded against the wrong field.

Pure Python and defensive: JPEG (APP1 "Exif") and TIFF-based files (TIFF, DNG). Any malformed
or truncated structure yields None, never an exception.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path

#: Enough for every JPEG we have seen: an EXIF segment is at most 64 KB and comes first.
JPEG_HEAD_BYTES = 256 * 1024
#: TIFF/DNG keep IFD0 and the GPS IFD near the start of the file.
TIFF_HEAD_BYTES = 1024 * 1024

_GPS_IFD_POINTER = 0x8825
_LAT_REF, _LAT, _LON_REF, _LON, _ALT_REF, _ALT = 1, 2, 3, 4, 5, 6
_TYPE_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}


@dataclass(frozen=True)
class GpsFix:
    lat: float
    lon: float
    alt_m: float | None = None


def read_gps(path: Path) -> GpsFix | None:
    """The photo's GPS position, or None when it has none (or the file is not JPEG/TIFF)."""
    try:
        with open(path, "rb") as f:
            head = f.read(4)
            f.seek(0)
            if head[:3] == b"\xff\xd8\xff":
                return gps_from_jpeg(f.read(JPEG_HEAD_BYTES))
            if head in (b"II*\x00", b"MM\x00*"):
                return gps_from_tiff(f.read(TIFF_HEAD_BYTES))
    except OSError:
        return None
    return None


def gps_from_jpeg(data: bytes) -> GpsFix | None:
    if data[:2] != b"\xff\xd8":
        return None
    i, n = 2, len(data)
    while i + 4 <= n:
        if data[i] != 0xFF:
            return None
        marker = data[i + 1]
        if marker == 0xFF:  # fill byte
            i += 1
            continue
        if marker in (0xD9, 0xDA):  # end of image / start of scan: no more metadata
            return None
        if 0xD0 <= marker <= 0xD7 or marker == 0x01:  # markers without a length
            i += 2
            continue
        (length,) = struct.unpack(">H", data[i + 2:i + 4])
        if length < 2:
            return None
        body = data[i + 4:i + 2 + length]
        if marker == 0xE1 and body[:6] == b"Exif\x00\x00":
            fix = gps_from_tiff(body[6:])
            if fix is not None:
                return fix
        i += 2 + length
    return None


def gps_from_tiff(t: bytes) -> GpsFix | None:
    """GPS from a TIFF structure (the body of a JPEG's EXIF segment, or a TIFF/DNG file)."""
    if len(t) < 8:
        return None
    if t[:2] == b"II":
        bo = "<"
    elif t[:2] == b"MM":
        bo = ">"
    else:
        return None
    if struct.unpack(bo + "H", t[2:4])[0] != 42:
        return None
    (ifd0,) = struct.unpack(bo + "I", t[4:8])
    entries = _ifd(t, bo, ifd0)
    if entries is None or _GPS_IFD_POINTER not in entries:
        return None
    pointer = _values(t, bo, entries[_GPS_IFD_POINTER])
    if not pointer:
        return None
    gps = _ifd(t, bo, int(pointer[0]))
    if gps is None:
        return None
    lat = _dms(_values(t, bo, gps.get(_LAT)))
    lon = _dms(_values(t, bo, gps.get(_LON)))
    if lat is None or lon is None:
        return None
    if _ascii(t, gps.get(_LAT_REF)) == "S":
        lat = -lat
    if _ascii(t, gps.get(_LON_REF)) == "W":
        lon = -lon
    # (0, 0) is what many cameras write when they had no satellite fix.
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0) or (abs(lat) < 1e-9 and abs(lon) < 1e-9):
        return None
    alt: float | None = None
    alt_v = _values(t, bo, gps.get(_ALT))
    if alt_v:
        alt = float(alt_v[0])
        ref = _values(t, bo, gps.get(_ALT_REF))
        if ref and int(ref[0]) == 1:
            alt = -alt
    return GpsFix(lat=round(lat, 8), lon=round(lon, 8), alt_m=round(alt, 2) if alt is not None else None)


# ---------------------------------------------------------------------------------------- helpers

_Entry = tuple[int, int, bytes]  # (type, count, the 4-byte value/offset field)


def _ifd(t: bytes, bo: str, offset: int) -> dict[int, _Entry] | None:
    if offset <= 0 or offset + 2 > len(t):
        return None
    (count,) = struct.unpack(bo + "H", t[offset:offset + 2])
    end = offset + 2 + count * 12
    if count == 0 or end > len(t):
        return None
    out: dict[int, _Entry] = {}
    for k in range(count):
        p = offset + 2 + k * 12
        tag, typ, n = struct.unpack(bo + "HHI", t[p:p + 8])
        out[tag] = (typ, n, t[p + 8:p + 12])
    return out


def _raw(t: bytes, bo: str, entry: _Entry | None) -> tuple[int, int, bytes] | None:
    if entry is None:
        return None
    typ, n, field = entry
    size = _TYPE_SIZES.get(typ)
    if size is None or n == 0 or n > 4096:
        return None
    total = size * n
    if total <= 4:
        return typ, n, field[:total]
    (off,) = struct.unpack(bo + "I", field)
    if off + total > len(t):
        return None
    return typ, n, t[off:off + total]


def _values(t: bytes, bo: str, entry: _Entry | None) -> list[float] | None:
    raw = _raw(t, bo, entry)
    if raw is None:
        return None
    typ, n, b = raw
    if typ in (5, 10):  # (signed) rational
        code = "I" if typ == 5 else "i"
        out = []
        for k in range(n):
            num, den = struct.unpack(bo + code * 2, b[k * 8:k * 8 + 8])
            if den == 0:
                return None
            out.append(num / den)
        return out
    code = {1: "B", 7: "B", 3: "H", 4: "I", 9: "i"}.get(typ)
    if code is None:
        return None
    return [float(v) for v in struct.unpack(bo + code * n, b)]


def _ascii(t: bytes, entry: _Entry | None) -> str:
    if entry is None or entry[0] != 2:
        return ""
    raw = _raw(t, "<", entry) if entry[1] <= 4 else None
    if raw is None:
        # Longer strings live at an offset; byte order only matters for that offset, and a
        # reference letter ("N", "E", ...) always fits inline, so this is enough.
        return ""
    return raw[2].split(b"\x00", 1)[0].decode("ascii", "ignore").strip().upper()


def _dms(v: list[float] | None) -> float | None:
    if not v:
        return None
    deg = v[0]
    minutes = v[1] if len(v) > 1 else 0.0
    seconds = v[2] if len(v) > 2 else 0.0
    if not (0 <= minutes < 60.0001 and 0 <= seconds < 60.0001) or deg < 0:
        return None
    return deg + minutes / 60.0 + seconds / 3600.0
