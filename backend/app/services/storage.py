"""Blob storage for flight images, scan photos, orthomosaics, surfaces and exports.

`LocalStorage` writes under WR_STORAGE_DIR. Keys are relative POSIX paths, so moving to object
storage (S3, MinIO, GCS) means implementing this same small interface.
"""

from __future__ import annotations

import os
import re
import shutil
import uuid
from functools import lru_cache
from pathlib import Path
from typing import BinaryIO

from app.core.config import get_settings
from app.core.errors import Invalid, NotFound, TooLarge

CHUNK = 1024 * 1024
_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def safe_name(name: str, fallback: str = "file") -> str:
    base = os.path.basename(name or "").strip() or fallback
    base = _SAFE.sub("_", base)[:120].strip("._") or fallback
    return base


class LocalStorage:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def path(self, key: str) -> Path:
        p = (self.root / key).resolve()
        if self.root not in p.parents and p != self.root:
            raise Invalid("Invalid storage key")
        return p

    def new_key(self, prefix: str, filename: str) -> str:
        return f"{prefix.strip('/')}/{uuid.uuid4().hex[:12]}_{safe_name(filename)}"

    def save_stream(self, key: str, src: BinaryIO, max_bytes: int | None = None) -> int:
        """Copy a stream to storage in chunks, enforcing a size cap. Returns bytes written."""
        dest = self.path(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        written = 0
        try:
            with tmp.open("wb") as out:
                while True:
                    chunk = src.read(CHUNK)
                    if not chunk:
                        break
                    written += len(chunk)
                    if max_bytes is not None and written > max_bytes:
                        raise TooLarge(f"File exceeds the {max_bytes // (1024 * 1024)} MB limit")
                    out.write(chunk)
            os.replace(tmp, dest)
        finally:
            if tmp.exists():
                tmp.unlink(missing_ok=True)
        return written

    def save_bytes(self, key: str, data: bytes) -> int:
        dest = self.path(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        tmp.write_bytes(data)
        os.replace(tmp, dest)
        return len(data)

    def open(self, key: str) -> Path:
        p = self.path(key)
        if not p.is_file():
            raise NotFound("Stored file not found")
        return p

    def exists(self, key: str | None) -> bool:
        return bool(key) and self.path(key).is_file()  # type: ignore[arg-type]

    def delete(self, key: str | None) -> None:
        if key:
            self.path(key).unlink(missing_ok=True)

    def delete_prefix(self, prefix: str) -> None:
        p = self.path(prefix)
        if p.is_dir():
            shutil.rmtree(p, ignore_errors=True)

    def dir(self, prefix: str) -> Path:
        p = self.path(prefix)
        p.mkdir(parents=True, exist_ok=True)
        return p


@lru_cache
def get_storage() -> LocalStorage:
    return LocalStorage(get_settings().storage_dir)


def sniff_image(head: bytes) -> str | None:
    """Content type from magic bytes; the client's declared type is not trusted."""
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    if head[:4] in (b"II*\x00", b"MM\x00*"):
        return "image/tiff"  # also DNG raw
    if head[4:12] in (b"ftypheic", b"ftypheix", b"ftypmif1", b"ftypmsf1"):
        return "image/heic"
    return None
