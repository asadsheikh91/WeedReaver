import { bounds, nearPoly, segDist } from '../data/geo'
import { InfestationField } from '../data/infestation'
import { fbm, mulberry32, value, white } from '../data/noise'
import type { Landscape, Patch, Pt } from '../data/types'

/**
 * Synthetic orthomosaic.
 *
 * Stands in for the georeferenced mosaic OpenDroneMap produces from a survey flight. The landscape is
 * modelled on the Rechna Doab in January: the canal-colony killa grid split into inheritance strips,
 * flood-irrigation beds, earthen bunds, lined watercourses, kikar and shisham on the field edges, a dera
 * with its buffalo shade and flowering mustard. Weed patches inside the surveyed parcel are rendered into
 * the canopy so the heat overlay lines up with something visible underneath it.
 *
 * This file is pure computation with no DOM access, so it runs identically in a worker pool.
 */

export interface AerialSpec {
  key: string
  target: Pt[] | null
  land: Landscape
  minX: number; minY: number; maxX: number; maxY: number
  ppm: number
  patches: Patch[] | null
  infestSeed: number
}

export const KW = 60.3
export const KH = 67.0

export function makeSpec(
  key: string, target: Pt[] | null, land: Landscape, patches: Patch[] | null, infestSeed: number, margin = 95,
): AerialSpec {
  const b = target ? bounds(target) : [0, 0, 120, 120]
  const minX = b[0] - margin, minY = b[1] - margin, maxX = b[2] + margin, maxY = b[3] + margin
  const longest = Math.max(maxX - minX, maxY - minY)
  const ppm = Math.min(6, Math.max(2.6, 2200 / longest))
  return { key, target, land, minX, minY, maxX, maxY, ppm, patches, infestSeed }
}

export const specSize = (s: AerialSpec) => ({
  w: Math.round((s.maxX - s.minX) * s.ppm),
  h: Math.round((s.maxY - s.minY) * s.ppm),
})

// crops
const WHEAT = 0, WHEAT_THIN = 1, BERSEEM = 2, MUSTARD = 3, CANE = 4, FALLOW = 5, POTATO = 6

function cropFor(h: number, mustard: number): number {
  if (h < mustard) return MUSTARD
  if (h < mustard + 0.5) return WHEAT
  if (h < mustard + 0.6) return WHEAT_THIN
  if (h < mustard + 0.71) return BERSEEM
  if (h < mustard + 0.78) return CANE
  if (h < mustard + 0.88) return FALLOW
  return POTATO
}

/** Renders rows [y0, y1) of the base land-cover layer as RGBA. Independent of every other row. */
export function renderBand(spec: AerialSpec, y0: number, y1: number): Uint8ClampedArray {
  const { w } = specSize(spec)
  const seed = spec.land.seed
  const inv = 1 / spec.ppm
  const out = new Uint8ClampedArray((y1 - y0) * w * 4)

  // Low-frequency fields sampled on a coarse lattice and interpolated: vigour, moisture, mid-scale mottling.
  const step = 4
  const lw = Math.floor(w / step) + 2
  const jStart = Math.floor(y0 / step)
  const jEnd = Math.floor((y1 - 1) / step) + 1
  const lattice = (freq: number, sd: number, oct: number) => {
    const arr = new Float32Array(lw * (jEnd + 1))
    for (let j = jStart; j <= jEnd; j++) {
      for (let i = 0; i < lw; i++) {
        arr[j * lw + i] = fbm((spec.minX + i * step * inv) * freq, (spec.minY + j * step * inv) * freq, sd, oct)
      }
    }
    return arr
  }
  const vigour = lattice(0.02, seed + 11, 4)
  const moist = lattice(0.007, seed + 23, 3)
  const mottle = lattice(0.16, seed + 29, 3)
  const lerp = (a: Float32Array, xi: number, yi: number) => {
    const i = Math.floor(xi / step), j = Math.floor(yi / step)
    const tx = (xi - i * step) / step, ty = (yi - j * step) / step
    const p = a[j * lw + i], q = a[j * lw + i + 1], r = a[(j + 1) * lw + i], s = a[(j + 1) * lw + i + 1]
    const top = p + (q - p) * tx
    return top + (r + (s - r) * tx - top) * ty
  }

  const target = spec.target
  const tb = target ? bounds(target) : null
  const infest = target && spec.patches ? new InfestationField(target, spec.patches, spec.infestSeed) : null

  // Weed surface on a 0.5 m lattice over the surveyed parcel.
  const wStep = 0.5
  const ww = tb ? Math.floor((tb[2] - tb[0]) / wStep) + 2 : 0
  const wh = tb ? Math.floor((tb[3] - tb[1]) / wStep) + 2 : 0
  const weed = new Float32Array(ww * wh)
  const broad = new Uint8Array(ww * wh)
  if (infest && tb) {
    // only the rows this band can touch
    const rowLo = Math.max(0, Math.floor((spec.minY + y0 * inv - tb[1]) / wStep) - 1)
    const rowHi = Math.min(wh - 1, Math.ceil((spec.minY + y1 * inv - tb[1]) / wStep) + 1)
    for (let j = rowLo; j <= rowHi; j++) {
      for (let i = 0; i < ww; i++) {
        const x = tb[0] + i * wStep, y = tb[1] + j * wStep
        weed[j * ww + i] = infest.valueAt(x, y)
        broad[j * ww + i] = infest.dominantAt(x, y)?.weedClass === 'BROADLEAF' ? 1 : 0
      }
    }
  }

  for (let yi = y0; yi < y1; yi++) {
    const y = spec.minY + (yi + 0.5) * inv
    let spans: number[] | null = null
    if (target && tb && y >= tb[1] - 2 && y <= tb[3] + 2) {
      const xs: number[] = []
      for (let k = 0; k < target.length; k++) {
        const a = target[k], b = target[(k + 1) % target.length]
        if (a.y > y !== b.y > y) xs.push(a.x + ((y - a.y) * (b.x - a.x)) / (b.y - a.y))
      }
      xs.sort((p, q) => p - q)
      spans = xs
    }
    for (let xi = 0; xi < w; xi++) {
      const x = spec.minX + (xi + 0.5) * inv
      const vig = lerp(vigour, xi, yi), mo = lerp(moist, xi, yi), mot = lerp(mottle, xi, yi)

      // ---- killa grid and inheritance strips of uneven width
      const ki = Math.floor(x / KW), kj = Math.floor(y / KH)
      const u = x - ki * KW, v = y - kj * KH
      const hk = white(ki, kj, seed)
      const split = hk < 0.36 ? 1 : hk < 0.68 ? 2 : hk < 0.84 ? 3 : -2
      const j1 = white(ki + 3, kj - 7, seed + 1), j2 = white(ki - 5, kj + 2, seed + 2)
      let sub: number, du: number, dv: number, along: number, across: number
      if (split > 0) {
        const c1 = KW * (split === 2 ? 0.38 + j1 * 0.24 : 0.24 + j1 * 0.14)
        const c2 = split === 3 ? KW * (0.58 + j2 * 0.16) : KW
        let lo: number, hi: number
        if (split === 1) { sub = 0; lo = 0; hi = KW }
        else if (u <= c1) { sub = 0; lo = 0; hi = c1 }
        else if (u <= c2) { sub = 1; lo = c1; hi = c2 }
        else { sub = 2; lo = c2; hi = KW }
        du = Math.min(u - lo, hi - u); dv = Math.min(v, KH - v); along = v; across = u - lo
      } else {
        const cut = KH * (0.4 + j1 * 0.2)
        sub = v > cut ? 1 : 0
        du = Math.min(u, KW - u)
        dv = sub === 0 ? Math.min(v, cut - v) : Math.min(v - cut, KH - v)
        along = u; across = sub === 0 ? v : v - cut
      }
      const killaEdge = Math.min(u, KW - u, v, KH - v)
      const edge = Math.min(du, dv)
      const ph = white(ki * 7 + sub * 3, kj * 13 + sub, seed + 3)
      let crop = cropFor(ph, spec.land.mustardBias)
      let bedLen = 8 + white(ki, kj + sub * 5, seed + 5) * 6
      let laneW = 9 + white(ki + sub, kj, seed + 6) * 8
      let parcelKey = ki * 31 + kj * 17 + sub
      const bundWobble = (value(x * 0.5, y * 0.5, seed + 67) - 0.5) * 0.5
      let bundW = (killaEdge < 1.5 ? 1.25 : 0.75) + bundWobble
      let isBund = edge < bundW || killaEdge < 1.1 + bundWobble
      let margin = edge

      // ---- the surveyed parcel overrides whatever the grid says
      let inTarget = false
      let tEdge = 99
      if (spans && target) {
        for (let k = 0; k + 1 < spans.length; k += 2) {
          if (x >= spans[k] && x <= spans[k + 1]) { inTarget = true; break }
        }
        if (tb && x >= tb[0] - 2 && x <= tb[2] + 2) {
          for (let e = 0; e < target.length; e++) {
            const a = target[e], b = target[(e + 1) % target.length]
            const d = segDist(x, y, a.x, a.y, b.x, b.y)
            if (d < tEdge) tEdge = d
          }
        }
        if (inTarget) {
          crop = WHEAT; bedLen = 11; laneW = 13; parcelKey = -1
          bundW = 1.2 + bundWobble; isBund = tEdge < bundW; margin = tEdge
        } else if (tEdge < 1.2 + bundWobble) isBund = true
      }

      // per-parcel and per-bed tone: every kiara is watered and fertilised on its own day
      const pt = white(parcelKey, 991, seed + 13) - 0.5
      const bed = Math.floor(along / bedLen), lane = Math.floor(across / laneW)
      const bt = white(bed * 13 + lane, parcelKey + lane * 7, seed + 17)
      const watered = bt < 0.1
      const bedTone = (bt - 0.5) * 8

      let r: number, g: number, b: number
      const tex = value(x * 1.3, y * 1.3, seed + 31)

      switch (crop) {
        case WHEAT: {
          const t = vig * 0.55 + mot * 0.45
          const band = Math.sin(across * 2.618) * 2.5
          r = 90 + t * 38 + band; g = 114 + t * 34 + band; b = 60 + t * 16
          break
        }
        case WHEAT_THIN: {
          const soil = fbm(x * 0.5, y * 0.5, seed + 7, 2)
          r = 116 + vig * 22 + soil * 34; g = 124 + vig * 20 + soil * 16; b = 82 + soil * 22
          break
        }
        case BERSEEM: {
          const t = vig * 0.5 + mot * 0.5
          r = 66 + t * 30; g = 104 + t * 34; b = 54 + t * 14
          if ((Math.floor(along / 16) + ki) % 3 === 0) { r += 34; g += 18; b += 24 }
          break
        }
        case MUSTARD: {
          const bloom = mot * 0.6 + tex * 0.4
          r = 150 + bloom * 50; g = 148 + bloom * 38; b = 66 + bloom * 10
          break
        }
        case CANE: {
          const coarse = value(x * 0.6, y * 0.6, seed + 19)
          const s = Math.sin(across * 4.8) * 4
          r = 72 + coarse * 30 + s; g = 92 + coarse * 28 + s; b = 62 + coarse * 16
          break
        }
        case FALLOW: {
          const furrow = Math.sin(along * 7.6 + tex * 2) * 6
          r = 158 + vig * 20 + mot * 16 + furrow; g = 138 + vig * 16 + mot * 12 + furrow; b = 108 + vig * 12 + mot * 8 + furrow
          if (mo > 0.56) { r -= 24; g -= 22; b -= 16 }
          break
        }
        default: {
          if (Math.sin(across * 6.9) > 0.1) { r = 98 + mot * 24; g = 116 + mot * 22; b = 70 }
          else { r = 134 + tex * 16; g = 118 + tex * 12; b = 94 + tex * 10 }
        }
      }

      r += pt * 16 + bedTone; g += pt * 12 + bedTone; b += pt * 8 + bedTone * 0.6
      if (watered && crop !== MUSTARD && crop !== CANE) { r -= 12; g -= 7; b -= 5 }

      // low ridges between beds and down the lane lines
      if (!isBund && crop !== CANE) {
        const a2 = along % bedLen, c2 = across % laneW
        const ridge = Math.min(a2, bedLen - a2, c2, laneW - c2)
        if (ridge < 0.35) { const k = 1 - ridge / 0.35; r += 16 * k; g += 12 * k; b += 12 * k }
      }
      if (!isBund && mo > 0.66 && crop !== MUSTARD) {
        const k = (mo - 0.66) * 2; r -= 20 * k; g -= 12 * k; b -= 8 * k
      }

      // weeds inside the surveyed parcel, rendered into the canopy
      if (inTarget && tb && !isBund && ww > 0) {
        const wi = Math.min(ww - 1, Math.max(0, Math.floor((x - tb[0]) / wStep)))
        const wj = Math.min(wh - 1, Math.max(0, Math.floor((y - tb[1]) / wStep)))
        const wv = weed[wj * ww + wi]
        if (wv > 0.03) {
          const vis = Math.sqrt(wv)
          if (broad[wj * ww + wi]) {
            // broadleaf: darker, coarse rosettes breaking the drill lines
            const speck = value(x * 2.6, y * 2.6, seed + 53)
            const k = Math.min(0.95, vis * (0.45 + speck * 0.7))
            r += (58 - r) * k * 0.75; g += (86 - g) * k * 0.6; b += (46 - b) * k * 0.6
            if (speck > 0.68) { r += 34 * vis; g += 38 * vis; b += 22 * vis }
          } else {
            // Phalaris minor: paler, glaucous, taller than the crop around it
            const k = Math.min(1, vis * (0.7 + tex * 0.45))
            r += (150 - r) * k * 0.72; g += (170 - g) * k * 0.62; b += (130 - b) * k * 0.75
          }
        }
      }

      if (isBund) {
        const bn = fbm(x * 0.9, y * 0.9, seed + 61, 2)
        const gk = Math.min(1, Math.max(0, (bn - 0.42) * 3))
        r = 164 + (112 - 164) * gk + tex * 10
        g = 150 + (122 - 150) * gk + tex * 10
        b = 118 + (82 - 118) * gk + tex * 6
      } else if (margin < bundW + 0.8) {
        const k = 1 - (margin - bundW) / 0.8
        r -= 12 * k; g -= 4 * k; b -= 10 * k
      }

      // Final grade, applied here so it runs in parallel: a touch of desaturation and lift (winter
      // haze), then sensor grain in 2 x 2 blocks. Vector colours are pre-graded to match (see `rgba`).
      const l = r * 0.3 + g * 0.59 + b * 0.11
      const grain = (white(xi >> 1, yi >> 1, seed + 41) - 0.5) * 9
      const o = ((yi - y0) * w + xi) * 4
      out[o] = (l + (r - l) * 0.82) * 0.9 + 17 + grain
      out[o + 1] = (l + (g - l) * 0.82) * 0.9 + 16 + grain
      out[o + 2] = (l + (b - l) * 0.82) * 0.88 + 16 + grain
      out[o + 3] = 255
    }
  }
  return out
}

// ------------------------------------------------------------------ vector layer ------------------

interface Tree { x: number; y: number; r: number; kind: number }
type Ctx = OffscreenCanvasRenderingContext2D

/** Same grade the base layer gets, so roads, roofs and trees sit in the same light as the fields. */
const rgba = (a: number, r: number, g: number, b: number) => {
  const l = r * 0.3 + g * 0.59 + b * 0.11
  const R = (l + (r - l) * 0.82) * 0.9 + 17, G = (l + (g - l) * 0.82) * 0.9 + 16, B = (l + (b - l) * 0.82) * 0.88 + 16
  return `rgba(${Math.round(R)},${Math.round(G)},${Math.round(B)},${a / 255})`
}

class Painter {
  constructor(readonly c: Ctx, readonly s: AerialSpec) {}
  X = (x: number) => (x - this.s.minX) * this.s.ppm
  Y = (y: number) => (y - this.s.minY) * this.s.ppm
  M = (m: number) => m * this.s.ppm

  private blur<T>(m: number, f: () => T): T {
    if (m <= 0) return f()
    this.c.filter = `blur(${(this.M(m) * 0.5).toFixed(2)}px)`
    const r = f()
    this.c.filter = 'none'
    return r
  }

  path(pts: Pt[]) {
    this.c.beginPath()
    pts.forEach((q, i) => (i === 0 ? this.c.moveTo(this.X(q.x), this.Y(q.y)) : this.c.lineTo(this.X(q.x), this.Y(q.y))))
  }

  stroke(pts: Pt[], widthM: number, color: string, blurM = 0) {
    this.blur(blurM, () => {
      this.c.lineCap = 'round'; this.c.lineJoin = 'round'
      this.c.lineWidth = this.M(widthM); this.c.strokeStyle = color
      this.path(pts); this.c.stroke()
    })
  }

  private offsetLine(pts: Pt[], d: number): Pt[] {
    if (pts.length < 2) return pts
    return pts.map((q, i) => {
      const a = pts[Math.max(0, i - 1)], b = pts[Math.min(pts.length - 1, i + 1)]
      const dx = b.x - a.x, dy = b.y - a.y
      const l = Math.max(Math.hypot(dx, dy), 0.001)
      return { x: q.x - (dy / l) * d, y: q.y + (dx / l) * d }
    })
  }

  private sample(pts: Pt[], every: number, rng: () => number, jitter: number, f: (p: Pt) => void) {
    for (let i = 0; i < pts.length - 1; i++) {
      const a = pts[i], b = pts[i + 1]
      const len = Math.hypot(b.x - a.x, b.y - a.y)
      let t = rng() * every
      while (t < len) {
        const k = t / len
        f({ x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k })
        t += every * (0.6 + rng() * jitter)
      }
    }
  }

  canal(line: Pt[], trees: Tree[], rng: () => number) {
    this.stroke(line, 40, rgba(255, 170, 154, 120), 1.2)
    this.stroke(line, 34, rgba(255, 184, 168, 134))
    this.stroke(this.offsetLine(line, 13.5), 3.2, rgba(255, 200, 186, 152))
    this.stroke(this.offsetLine(line, -13.5), 3.2, rgba(255, 200, 186, 152))
    this.stroke(line, 19, rgba(255, 150, 142, 112))
    this.stroke(line, 17, rgba(255, 88, 104, 88))
    this.stroke(line, 10, rgba(255, 76, 94, 82), 1.8)
    this.stroke(this.offsetLine(line, 7.9), 0.6, rgba(110, 210, 200, 170))
    for (const off of [19.5, -19.5]) {
      this.sample(this.offsetLine(line, off), 5.5, rng, 0.8, (q) => trees.push({ x: q.x, y: q.y, r: 2.4 + rng() * 1.6, kind: 1 }))
    }
  }

  watercourse(line: Pt[], trees: Tree[], rng: () => number) {
    this.stroke(line, 4.2, rgba(255, 150, 136, 104))
    this.stroke(line, 3.0, rgba(255, 118, 122, 88))
    this.stroke(line, 1.3, rgba(255, 62, 80, 70))
    this.sample(this.offsetLine(line, 3.2), 16, rng, 2.4, (q) => {
      if (rng() < 0.45) trees.push({ x: q.x, y: q.y, r: 2.6 + rng() * 2.8, kind: 0 })
    })
  }

  road(line: Pt[], trees: Tree[], rng: () => number) {
    this.stroke(line, 6.4, rgba(170, 176, 160, 128), 0.8)
    this.stroke(line, 4.8, rgba(255, 194, 178, 144))
    this.stroke(this.offsetLine(line, 1.05), 0.55, rgba(150, 164, 146, 114))
    this.stroke(this.offsetLine(line, -1.05), 0.55, rgba(150, 164, 146, 114))
    for (const off of [5.5, -5.5]) {
      this.sample(this.offsetLine(line, off), 11, rng, 1.6, (q) => {
        if (rng() < 0.5) trees.push({ x: q.x, y: q.y, r: 3 + rng() * 2.6, kind: 2 })
      })
    }
  }

  rect(x: number, y: number, w: number, h: number) { this.c.fillRect(this.X(x), this.Y(y), this.M(w), this.M(h)) }

  building(x: number, y: number, w: number, h: number, roof: string, height = 3.2) {
    const c = this.c
    // soft drop shadow via the native shadow path: far cheaper than a filter per rectangle
    c.save()
    c.shadowColor = 'rgba(20,24,18,0.5)'
    c.shadowBlur = this.M(0.9)
    c.shadowOffsetX = -this.M(height * 0.32); c.shadowOffsetY = -this.M(height * 0.25)
    c.fillStyle = roof
    this.rect(x, y, w, h)
    c.restore()
    c.lineWidth = this.M(0.35); c.strokeStyle = rgba(90, 255, 255, 240)
    c.strokeRect(this.X(x + 0.2), this.Y(y + 0.2), this.M(w - 0.4), this.M(h - 0.4))
  }

  private disc(x: number, y: number, r: number, color: string) {
    this.c.fillStyle = color
    this.c.beginPath(); this.c.arc(this.X(x), this.Y(y), this.M(r), 0, Math.PI * 2); this.c.fill()
  }
  private oval(x0: number, y0: number, x1: number, y1: number, color: string) {
    this.c.fillStyle = color
    this.c.beginPath()
    this.c.ellipse(this.X((x0 + x1) / 2), this.Y((y0 + y1) / 2), this.M(Math.abs(x1 - x0) / 2), this.M(Math.abs(y1 - y0) / 2), 0, 0, Math.PI * 2)
    this.c.fill()
  }

  farmstead(at: Pt, trees: Tree[], rng: () => number) {
    const c = this.c
    c.fillStyle = rgba(255, 176, 158, 124)
    c.beginPath(); c.roundRect(this.X(at.x - 20), this.Y(at.y - 14), this.M(40), this.M(30), this.M(2)); c.fill()
    c.lineWidth = this.M(0.5); c.strokeStyle = rgba(255, 150, 120, 96)
    c.strokeRect(this.X(at.x - 19), this.Y(at.y - 13), this.M(38), this.M(28))
    this.building(at.x - 17, at.y - 11, 16, 7, rgba(255, 168, 164, 154))
    this.building(at.x + 4, at.y - 11, 12, 6, rgba(255, 146, 94, 70))
    this.building(at.x + 6, at.y + 5, 11, 7, rgba(255, 150, 136, 98), 2.4)
    for (let i = 0; i < 4; i++) this.oval(at.x - 12 + i * 2.4, at.y + 6 + (i % 2), at.x - 10.6 + i * 2.4, at.y + 8.4 + (i % 2), rgba(255, 40, 36, 34))
    this.disc(at.x - 14, at.y + 12, 2.1, rgba(255, 214, 196, 138))
    for (let i = 0; i < 6; i++) this.disc(at.x - 4 + i * 1.1, at.y + 13, 0.45, rgba(255, 96, 82, 62))
    for (let i = 0; i < 7; i++) trees.push({ x: at.x - 24 + rng() * 50, y: at.y - 20 + rng() * 42, r: 3 + rng() * 3.5, kind: 0 })
  }

  tubewell(at: Pt, trees: Tree[]) {
    this.c.fillStyle = rgba(255, 172, 156, 120)
    this.rect(at.x - 10, at.y - 11, 20, 22)
    this.building(at.x - 4, at.y - 6, 5, 4, rgba(255, 178, 174, 164), 2.6)
    this.c.fillStyle = rgba(255, 128, 86, 66); this.rect(at.x + 2, at.y - 5, 3, 3)
    this.c.fillStyle = rgba(255, 64, 84, 76); this.rect(at.x + 2.6, at.y - 4.4, 1.8, 1.8)
    this.stroke([{ x: at.x + 3.5, y: at.y - 2 }, { x: at.x + 3.5, y: at.y + 10 }, { x: at.x + 12, y: at.y + 12 }], 1.1, rgba(255, 66, 84, 72))
    trees.push({ x: at.x - 6, y: at.y + 5, r: 5.2, kind: 0 })
  }

  village(at: Pt, trees: Tree[], rng: () => number) {
    const c = this.c
    const n = 28
    this.blur(2, () => {
      c.fillStyle = rgba(255, 166, 148, 118)
      c.beginPath()
      for (let i = 0; i <= n; i++) {
        const a = (i / n) * 6.2832
        const rr = 1 + 0.18 * Math.sin(a * 3 + 1.2) + 0.1 * Math.sin(a * 5)
        const qx = at.x + Math.cos(a) * 95 * rr, qy = at.y + Math.sin(a) * 62 * rr
        if (i === 0) c.moveTo(this.X(qx), this.Y(qy)); else c.lineTo(this.X(qx), this.Y(qy))
      }
      c.closePath(); c.fill()
    })
    this.oval(at.x + 58, at.y + 18, at.x + 88, at.y + 40, rgba(255, 88, 104, 78))
    this.oval(at.x + 62, at.y + 21, at.x + 84, at.y + 37, rgba(255, 66, 86, 72))
    const roofs = [
      rgba(255, 160, 156, 148), rgba(255, 188, 182, 170), rgba(255, 146, 96, 74),
      rgba(255, 164, 140, 108), rgba(255, 176, 170, 160), rgba(255, 128, 120, 112),
    ]
    for (let gy = at.y - 58; gy < at.y + 58; gy += 12.5 + rng() * 1.5) {
      for (let gx = at.x - 92; gx < at.x + 92; gx += 13.5 + rng() * 1.5) {
        const nx = (gx - at.x) / 95, ny = (gy - at.y) / 62
        const inside = nx * nx + ny * ny < 0.86
        const pond = gx > at.x + 52 && gy > at.y + 12
        if (inside && !pond && rng() < 0.86) {
          const cw = 9 + rng() * 4, ch = 8 + rng() * 4
          c.fillStyle = rgba(255, 180, 164, 132); this.rect(gx, gy, cw, ch)
          const roof = roofs[Math.floor(rng() * roofs.length)]
          if (rng() < 0.5) this.building(gx + 0.4, gy + 0.4, cw - 0.8, ch * 0.5, roof)
          else this.building(gx + 0.4, gy + 0.4, cw * 0.55, ch - 0.8, roof)
          if (rng() < 0.12) trees.push({ x: gx + cw * 0.7, y: gy + ch * 0.7, r: 2.6 + rng() * 2, kind: 0 })
        }
      }
    }
    this.building(at.x - 8, at.y - 6, 16, 12, rgba(255, 232, 228, 216), 4.5)
    this.disc(at.x - 1, at.y - 1, 3.4, rgba(255, 214, 210, 200))
    this.disc(at.x - 1.8, at.y - 1.8, 2.1, rgba(255, 246, 244, 236))
    for (let i = 0; i < 10; i++) trees.push({ x: at.x - 100 + rng() * 200, y: at.y - 66 + rng() * 132, r: 3 + rng() * 3, kind: 0 })
  }

  trees(list: Tree[]) {
    const c = this.c
    // winter-morning sun from the south-east: shadows fall north-west
    this.blur(0.9, () => {
      c.fillStyle = rgba(120, 18, 26, 16)
      for (const t of list) {
        const off = t.r * 0.9
        c.beginPath()
        c.ellipse(this.X(t.x - off), this.Y(t.y - off * 0.8), this.M(t.r), this.M(t.r * 0.8), 0, 0, Math.PI * 2)
        c.fill()
      }
    })
    const rng = mulberry32(this.s.land.seed + 5)
    for (const t of list) {
      const base = t.kind === 1 ? rgba(255, 62, 84, 60) : t.kind === 2 ? rgba(255, 54, 76, 44) : rgba(255, 50, 70, 40)
      this.disc(t.x, t.y, t.r, base)
      for (let i = 0; i < 6; i++) {
        const a = rng() * 6.28, d = rng() * t.r * 0.55, rr = t.r * (0.3 + rng() * 0.3), shade = 0.8 + rng() * 0.5
        this.disc(t.x + Math.cos(a) * d, t.y + Math.sin(a) * d, rr, rgba(255, Math.round(68 * shade), Math.round(92 * shade), Math.round(54 * shade)))
      }
      this.disc(t.x + t.r * 0.3, t.y + t.r * 0.3, t.r * 0.45, rgba(90, 170, 190, 120))
    }
  }
}

/** Draws roads, water, buildings and trees over the base layer, then grades the whole frame. */
export function finishImage(spec: AerialSpec, base: Uint8ClampedArray): OffscreenCanvas {
  const { w, h } = specSize(spec)
  const canvas = new OffscreenCanvas(w, h)
  const c = canvas.getContext('2d') as Ctx
  c.putImageData(new ImageData(base as Uint8ClampedArray<ArrayBuffer>, w, h), 0, 0)

  const p = new Painter(c, spec)
  const rng = mulberry32(spec.land.seed)
  const trees: Tree[] = []
  const land = spec.land
  if (land.canal) p.canal(land.canal, trees, rng)
  if (land.watercourse) p.watercourse(land.watercourse, trees, rng)
  if (land.road) p.road(land.road, trees, rng)
  if (land.village) p.village(land.village, trees, rng)
  if (land.farmstead) p.farmstead(land.farmstead, trees, rng)
  if (land.tubewell) p.tubewell(land.tubewell, trees)

  // kikar on bund intersections, away from the surveyed parcel
  for (let ki = Math.floor(spec.minX / KW); ki * KW < spec.maxX; ki++) {
    for (let kj = Math.floor(spec.minY / KH); kj * KH < spec.maxY; kj++) {
      const hh = white(ki, kj, spec.land.seed + 97)
      const tx = ki * KW, ty = kj * KH
      const clear = !spec.target || !nearPoly(spec.target, tx, ty, 8)
      if (hh < 0.22 && clear) trees.push({ x: tx + (hh - 0.1) * 20, y: ty + 0.6, r: 2.8 + hh * 10, kind: 0 })
    }
  }
  p.trees(trees)

  return canvas
}
