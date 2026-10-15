"""Shows a stitched orthomosaic on a web map: XYZ tiles in Web Mercator, read from the GeoTIFF.

OpenDroneMap writes a cloud-optimised GeoTIFF (internally tiled, with overviews) in the flight's
UTM zone. A tile request reads only the window and overview level it needs, reprojects it to
Web Mercator and encodes it, so a multi-gigabyte mosaic is browsable without being pre-cut into
thousands of files. This is the approach WebODM takes (rio-tiler over the orthophoto).

Pixels outside the stitched area (ODM's alpha band) come back transparent, and a tile with no
stitched pixels at all is reported as empty so the client can skip it.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Literal

log = logging.getLogger("weedreaver.pipeline.tiles")

TILE_SIZE = 256
#: Deepest zoom a client may ask for. Web Mercator z24 is ~1 cm/px at the equator.
MAX_REQUEST_ZOOM = 24
TileFormat = Literal["webp", "png"]

# Only the GeoTIFF is opened; skip listing its folder (flight folders hold thousands of images).
os.environ.setdefault("GDAL_DISABLE_READDIR_ON_OPEN", "EMPTY_DIR")


class OrthomosaicUnreadable(ValueError):
    """The file is not a georeferenced raster this module can serve."""


@dataclass(frozen=True)
class OrthoInfo:
    west: float
    south: float
    east: float
    north: float
    #: Zoom at which the whole mosaic fits in about one tile, and the zoom of its native pixels.
    min_zoom: int
    max_zoom: int
    width: int
    height: int
    bands: int
    dtype: str
    epsg: int | None
    #: Band indexes drawn as the picture (RGB, or one grey band).
    indexes: tuple[int, ...]
    #: Per-band (low, high) stretch for non-8-bit rasters; None for ordinary 8-bit RGB.
    rescale: tuple[tuple[float, float], ...] | None
    size_bytes: int


def _readers():
    try:
        from rio_tiler.constants import WGS84_CRS
        from rio_tiler.errors import TileOutsideBounds
        from rio_tiler.io import Reader
    except ImportError as exc:  # pragma: no cover - both are in requirements.txt
        raise OrthomosaicUnreadable("rasterio and rio-tiler are not installed on this server") from exc
    return Reader, WGS84_CRS, TileOutsideBounds


def describe(path: Path) -> OrthoInfo:
    """What a map needs to show the orthomosaic. Cached until the file changes."""
    try:
        st = path.stat()
    except OSError as exc:
        raise OrthomosaicUnreadable("The orthomosaic file is missing") from exc
    return _describe(str(path), st.st_mtime_ns, st.st_size)


@lru_cache(maxsize=256)
def _describe(path: str, _mtime_ns: int, size: int) -> OrthoInfo:
    Reader, WGS84_CRS, _ = _readers()
    from rasterio.enums import ColorInterp
    from rasterio.errors import RasterioError

    try:
        with Reader(path) as src:
            ds = src.dataset
            if ds.crs is None:
                raise OrthomosaicUnreadable("The orthomosaic carries no coordinate reference system")
            interp = list(ds.colorinterp)
            colour = [i + 1 for i, ci in enumerate(interp) if ci != ColorInterp.alpha]
            if not colour:
                raise OrthomosaicUnreadable("The orthomosaic has no colour bands")
            indexes = tuple(colour[:3]) if len(colour) >= 3 else (colour[0],)
            w, s, e, n = src.get_geographic_bounds(WGS84_CRS)
            if not (-180 <= w < e <= 180 and -90 <= s < n <= 90):
                raise OrthomosaicUnreadable("The orthomosaic's footprint is not a valid place on Earth")
            dtype = str(ds.dtypes[indexes[0] - 1])
            rescale = None
            if dtype != "uint8":
                rescale = _stretch(src, indexes)
            return OrthoInfo(
                west=w, south=s, east=e, north=n, min_zoom=int(src.minzoom), max_zoom=int(src.maxzoom),
                width=ds.width, height=ds.height, bands=ds.count, dtype=dtype,
                epsg=ds.crs.to_epsg(), indexes=indexes, rescale=rescale, size_bytes=size,
            )
    except OrthomosaicUnreadable:
        raise
    except (RasterioError, OSError, ValueError) as exc:
        raise OrthomosaicUnreadable(f"The orthomosaic cannot be read: {exc}") from exc


def _stretch(src, indexes: tuple[int, ...]) -> tuple[tuple[float, float], ...]:
    """2nd-98th percentile per band from a small preview: how a 16-bit or float mosaic is shown."""
    import numpy as np

    img = src.preview(indexes=indexes, max_size=512)
    out = []
    for b in range(img.count):
        valid = img.array[b].compressed() if np.ma.isMaskedArray(img.array) else img.data[b][img.mask > 0]
        if valid.size == 0:
            out.append((0.0, 1.0))
            continue
        lo, hi = np.percentile(valid, [2, 98])
        out.append((float(lo), float(hi) if hi > lo else float(lo) + 1.0))
    return tuple(out)


def tile_in_range(z: int, x: int, y: int) -> bool:
    return 0 <= z <= MAX_REQUEST_ZOOM and 0 <= x < (1 << z) and 0 <= y < (1 << z)


def render_tile(path: Path, z: int, x: int, y: int, fmt: TileFormat = "webp") -> bytes | None:
    """One 256 px tile, or None when the mosaic has no pixels there."""
    meta = describe(path)
    Reader, _, TileOutsideBounds = _readers()
    from rasterio.errors import RasterioError

    try:
        with Reader(str(path)) as src:
            if not src.tile_exists(x, y, z):
                return None
            img = src.tile(x, y, z, tilesize=TILE_SIZE, indexes=meta.indexes)
    except TileOutsideBounds:
        return None
    except (RasterioError, OSError) as exc:
        raise OrthomosaicUnreadable(f"The orthomosaic cannot be read: {exc}") from exc
    if not img.mask.any():
        return None
    if meta.rescale:
        img.rescale(in_range=meta.rescale)
    if fmt == "png":
        return img.render(img_format="PNG")
    # Lossy WebP with alpha: about a fifth of a PNG's size at no visible cost for aerial imagery.
    return img.render(img_format="WEBP", QUALITY=88)
