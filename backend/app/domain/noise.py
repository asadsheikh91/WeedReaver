"""Deterministic hash noise and PRNG, vectorised.

Bit-for-bit ports of the dashboard's `noise.ts` (32-bit integer semantics, done in uint64 and
masked), so a grid rasterised here reproduces the numbers the web client computes.
"""

from __future__ import annotations

import numpy as np

_M32 = np.uint64(0xFFFFFFFF)


def _u64(a: np.ndarray | int) -> np.ndarray:
    """Two's-complement wrap of (possibly negative) integers into [0, 2^32) as uint64."""
    return (np.asarray(a, dtype=np.int64) & 0xFFFFFFFF).astype(np.uint64)


def _imul(a: np.ndarray, b: np.ndarray | np.uint64) -> np.ndarray:
    return (a * b) & _M32


def hash2(x: np.ndarray, y: np.ndarray, seed: int) -> np.ndarray:
    xs, ys = _u64(x), _u64(y)
    s = _u64(seed)
    with np.errstate(over="ignore"):
        h = (_imul(xs, np.uint64(374761393)) + _imul(ys, np.uint64(668265263)) + _imul(s, np.uint64(1442695041))) & _M32
        h = _imul(h ^ (h >> np.uint64(13)), np.uint64(1274126177))
        h = h ^ (h >> np.uint64(16))
    return (h & np.uint64(0x7FFFFFFF)).astype(np.float64) / 2147483647.0


def _smooth(t: np.ndarray) -> np.ndarray:
    return t * t * (3.0 - 2.0 * t)


def value(x: np.ndarray, y: np.ndarray, seed: int) -> np.ndarray:
    xf = np.floor(x)
    yf = np.floor(y)
    xi = xf.astype(np.int64)
    yi = yf.astype(np.int64)
    tx = _smooth(x - xf)
    ty = _smooth(y - yf)
    a = hash2(xi, yi, seed)
    b = hash2(xi + 1, yi, seed)
    c = hash2(xi, yi + 1, seed)
    d = hash2(xi + 1, yi + 1, seed)
    top = a + (b - a) * tx
    bot = c + (d - c) * tx
    return top + (bot - top) * ty


def fbm(x: np.ndarray, y: np.ndarray, seed: int, octaves: int = 4) -> np.ndarray:
    amp, freq, norm = 0.5, 1.0, 0.0
    total = np.zeros(np.broadcast(x, y).shape, dtype=np.float64)
    for o in range(octaves):
        total += value(x * freq, y * freq, seed + o * 101) * amp
        norm += amp
        amp *= 0.5
        freq *= 2.03
    return total / norm


def mulberry32(seed: int, n: int) -> np.ndarray:
    """The first n outputs of mulberry32(seed), computed in one vectorised pass.

    The generator's state advances by a constant each call, so the k-th state is
    seed + (k + 1) * 0x6D2B79F5 (mod 2^32) and every output can be computed independently.
    """
    if n <= 0:
        return np.zeros(0, dtype=np.float64)
    k = np.arange(1, n + 1, dtype=np.uint64)
    with np.errstate(over="ignore"):
        a = (_u64(seed) + k * np.uint64(0x6D2B79F5)) & _M32
        t = _imul(a ^ (a >> np.uint64(15)), a | np.uint64(1))
        t = ((t + _imul(t ^ (t >> np.uint64(7)), t | np.uint64(61))) & _M32) ^ t
        out = (t ^ (t >> np.uint64(14))) & _M32
    return out.astype(np.float64) / 4294967296.0
