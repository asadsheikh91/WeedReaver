/** Deterministic hash-based value noise, matching the Android renderer so both surfaces draw the same land. */

export function hash(x: number, y: number, seed: number): number {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(seed, 1442695041)) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  h = h ^ (h >>> 16)
  return (h & 0x7fffffff) / 2147483647
}

const smooth = (t: number) => t * t * (3 - 2 * t)

export function value(x: number, y: number, seed: number): number {
  const xi = Math.floor(x), yi = Math.floor(y)
  const tx = smooth(x - xi), ty = smooth(y - yi)
  const a = hash(xi, yi, seed), b = hash(xi + 1, yi, seed)
  const c = hash(xi, yi + 1, seed), d = hash(xi + 1, yi + 1, seed)
  const top = a + (b - a) * tx
  const bot = c + (d - c) * tx
  return top + (bot - top) * ty
}

export function fbm(x: number, y: number, seed: number, octaves = 4): number {
  let amp = 0.5, freq = 1, sum = 0, norm = 0
  for (let o = 0; o < octaves; o++) {
    sum += value(x * freq, y * freq, seed + o * 101) * amp
    norm += amp
    amp *= 0.5
    freq *= 2.03
  }
  return sum / norm
}

export const white = (x: number, y: number, seed: number) => hash(x, y, seed)

/** Small seeded PRNG for anything that needs a stream rather than a lattice. */
export function mulberry32(seed: number) {
  let a = seed | 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
