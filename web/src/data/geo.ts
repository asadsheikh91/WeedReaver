import type { FieldParcel, Pt } from './types'

export const ACRE_SQM = 4046.86
export const dist = (a: Pt, b: Pt) => Math.hypot(a.x - b.x, a.y - b.y)

export function area(poly: Pt[]): number {
  if (poly.length < 3) return 0
  let s = 0
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length]
    s += a.x * b.y - b.x * a.y
  }
  return Math.abs(s / 2)
}

export function perimeter(poly: Pt[], closed = true): number {
  if (poly.length < 2) return 0
  let s = 0
  for (let i = 0; i < poly.length - 1; i++) s += dist(poly[i], poly[i + 1])
  if (closed && poly.length > 2) s += dist(poly[poly.length - 1], poly[0])
  return s
}

export function contains(poly: Pt[], x: number, y: number): boolean {
  let inside = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i], b = poly[j]
    if (a.y > y !== b.y > y && x < ((b.x - a.x) * (y - a.y)) / (b.y - a.y) + a.x) inside = !inside
  }
  return inside
}

export function centroid(poly: Pt[]): Pt {
  return {
    x: poly.reduce((s, p) => s + p.x, 0) / poly.length,
    y: poly.reduce((s, p) => s + p.y, 0) / poly.length,
  }
}

export type Bounds = [number, number, number, number]
export function bounds(poly: Pt[]): Bounds {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const p of poly) {
    if (p.x < minX) minX = p.x
    if (p.y < minY) minY = p.y
    if (p.x > maxX) maxX = p.x
    if (p.y > maxY) maxY = p.y
  }
  return [minX, minY, maxX, maxY]
}

export function segDist(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax, dy = by - ay
  const l2 = dx * dx + dy * dy
  const t = l2 === 0 ? 0 : Math.min(1, Math.max(0, ((px - ax) * dx + (py - ay) * dy) / l2))
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

export function nearPoly(poly: Pt[], x: number, y: number, d: number): boolean {
  if (contains(poly, x, y)) return true
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length]
    if (segDist(x, y, a.x, a.y, b.x, b.y) < d) return true
  }
  return false
}

export function bearing(from: Pt, to: Pt): number {
  const deg = (Math.atan2(to.x - from.x, -(to.y - from.y)) * 180) / Math.PI
  return (deg + 360) % 360
}
const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW']
export const compassShort = (deg: number) => COMPASS[Math.floor(((deg + 22.5) % 360) / 45)]

// ---- derived facts about a parcel, all computed from its vertices -----------------------

export const fieldAreaSqm = (f: { boundary: Pt[] }) => area(f.boundary)
export const fieldAcres = (f: { boundary: Pt[] }) => area(f.boundary) / ACRE_SQM
export const fieldHectares = (f: { boundary: Pt[] }) => area(f.boundary) / 10000
export const fieldPerimeter = (f: { boundary: Pt[] }) => perimeter(f.boundary)
export const kanalMarla = (acres: number) => {
  const k = Math.floor(acres * 8)
  const m = Math.round((acres * 8 - k) * 20)
  return m === 20 ? { kanal: k + 1, marla: 0 } : { kanal: k, marla: m }
}

/** Local metric frame → WGS84, valid over the few hundred metres a parcel spans. */
export function toLatLon(f: Pick<FieldParcel, 'lat' | 'lon'>, p: Pt): [number, number] {
  const dLat = -p.y / 111320
  const dLon = p.x / (111320 * Math.cos((f.lat * Math.PI) / 180))
  return [f.lat + dLat, f.lon + dLon]
}

/** Douglas-Peucker. The phone applies the same simplification at 2 m during a boundary walk. */
export function simplify(pts: Pt[], tolerance: number): Pt[] {
  if (pts.length < 3) return pts
  const keep = new Array<boolean>(pts.length).fill(false)
  keep[0] = keep[pts.length - 1] = true
  const seg = (s: number, e: number) => {
    if (e <= s + 1) return
    const a = pts[s], b = pts[e]
    const dx = b.x - a.x, dy = b.y - a.y
    const len = Math.max(Math.hypot(dx, dy), 1e-3)
    let best = -1, bestD = 0
    for (let i = s + 1; i < e; i++) {
      const p = pts[i]
      const d = Math.abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
      if (d > bestD) { bestD = d; best = i }
    }
    if (bestD > tolerance && best > 0) { keep[best] = true; seg(s, best); seg(best, e) }
  }
  seg(0, pts.length - 1)
  return pts.filter((_, i) => keep[i])
}

/** Inverse of toLatLon. */
export function fromLatLon(f: Pick<FieldParcel, 'lat' | 'lon'>, lat: number, lon: number): Pt {
  return { x: (lon - f.lon) * 111320 * Math.cos((f.lat * Math.PI) / 180), y: -(lat - f.lat) * 111320 }
}

const cross = (o: Pt, a: Pt, b: Pt) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
const properCross = (a: Pt, b: Pt, c: Pt, d: Pt) =>
  cross(c, d, a) * cross(c, d, b) < 0 && cross(a, b, c) * cross(a, b, d) < 0

/** Size of an outline drawn in WGS84, measured in the same local frame the server stores it in. */
export function measureLatLon(ring: { lat: number; lon: number }[]): { sqm: number; acres: number; perimeterM: number; crosses?: boolean } {
  if (ring.length < 3) return { sqm: 0, acres: 0, perimeterM: 0 }
  const anchor = { lat: Math.max(...ring.map((p) => p.lat)), lon: Math.min(...ring.map((p) => p.lon)) }
  const poly = ring.map((p) => fromLatLon(anchor, p.lat, p.lon))
  const n = poly.length
  let crosses = false
  for (let i = 0; i < n && !crosses; i++)
    for (let j = i + 2; j < n; j++)
      if (!(i === 0 && j === n - 1) && properCross(poly[i], poly[(i + 1) % n], poly[j], poly[(j + 1) % n])) { crosses = true; break }
  const sqm = area(poly)
  return { sqm, acres: sqm / ACRE_SQM, perimeterM: perimeter(poly), crosses }
}
