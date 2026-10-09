import { contains, bounds, dist } from './geo'
import { fbm, mulberry32 } from './noise'
import type { GridCell, GridSize, Patch, Pt, RasterGrid, Severity, TreatmentZone, WeedClass } from './types'

/**
 * Deterministic synthetic infestation surface standing in for the segmentation output.
 *
 * Weeds are spatially aggregated (spec §32, claim B), so the surface is built from a small number
 * of lobed patches, stretched along the drill direction. Rasterising the same surface at 1, 2 and
 * 5 m reproduces the trade recorded in spec §31: a cell is treated if any part of it crosses the
 * threshold, so coarsening the grid raises the treated-area fraction.
 */
export function band(infestPct: number): Severity {
  if (infestPct < 10) return 'CLEAN'
  if (infestPct <= 30) return 'MODERATE'
  return 'HEAVY'
}

export class InfestationField {
  readonly minX: number
  readonly minY: number
  readonly widthM: number
  readonly heightM: number

  constructor(readonly boundary: Pt[], readonly patches: Patch[], readonly seed: number) {
    const b = bounds(boundary)
    this.minX = Math.floor(b[0])
    this.minY = Math.floor(b[1])
    this.widthM = b[2] - this.minX
    this.heightM = b[3] - this.minY
  }

  private lobes(p: Patch, angle: number) {
    const ph = p.label.charCodeAt(0) * 1.7
    return 1 + 0.22 * Math.sin(3 * angle + ph) + 0.12 * Math.sin(5 * angle - ph * 0.6)
  }

  patchValue(p: Patch, x: number, y: number): number {
    const dx = (x - p.cx) / p.stretch
    const dy = y - p.cy
    const d = Math.hypot(dx, dy)
    const r = p.radiusM * this.lobes(p, Math.atan2(dy, dx))
    if (d >= r) return 0
    const t = 1 - d / r
    return p.peak * t * t
  }

  /** Sparse background: isolated plants that sit well below the prescription threshold. */
  private scatter(x: number, y: number) {
    const n = fbm(x * 0.11, y * 0.11, this.seed, 3)
    return Math.max(0, (n - 0.62) * 0.28)
  }

  valueAt(x: number, y: number): number {
    let v = this.scatter(x, y)
    for (const p of this.patches) v += this.patchValue(p, x, y)
    return Math.min(1, Math.max(0, v))
  }

  dominantAt(x: number, y: number): Patch | undefined {
    let best: Patch | undefined
    let bestScore = -Infinity
    for (const p of this.patches) {
      const s = this.patchValue(p, x, y) - Math.hypot(x - p.cx, y - p.cy) * 0.0005
      if (s > bestScore) { bestScore = s; best = p }
    }
    return best
  }

  /** Scale every patch peak; used to synthesise a post-treatment follow-up survey. */
  scaled(factors: Record<string, number>, fallback: number): InfestationField {
    return new InfestationField(
      this.boundary,
      this.patches.map((p) => ({ ...p, peak: Math.min(1, Math.max(0, p.peak * (factors[p.label] ?? fallback))) })),
      this.seed + 7,
    )
  }

  rasterise(size: GridSize, thresholdPct: number): RasterGrid {
    const cell = size
    const cols = Math.max(1, Math.round(this.widthM / cell))
    const rows = Math.max(1, Math.round(this.heightM / cell))
    const rng = mulberry32(this.seed * 31 + size)
    const n = size === 1 ? 2 : 3
    const cells: GridCell[] = new Array(cols * rows)
    let total = 0, flagged = 0, abstainedN = 0
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const x0 = this.minX + c * cell
        const y0 = this.minY + r * cell
        const idx = r * cols + c
        if (!contains(this.boundary, x0 + cell / 2, y0 + cell / 2)) {
          cells[idx] = { col: c, row: r, infestPct: 0, weedClass: 'CROP', confidence: 1, abstained: false, treated: false, inside: false }
          continue
        }
        let sum = 0, max = 0
        for (let sy = 0; sy < n; sy++) for (let sx = 0; sx < n; sx++) {
          const v = this.valueAt(x0 + (cell * (sx + 0.5)) / n, y0 + (cell * (sy + 0.5)) / n)
          sum += v
          if (v > max) max = v
        }
        const mean = sum / (n * n)
        const infestPct = mean * 100
        const patch = this.dominantAt(x0 + cell / 2, y0 + cell / 2)
        const cls: WeedClass = infestPct < 4 ? 'CROP' : patch?.weedClass ?? 'GRASS'
        // Confidence is lowest where crop and weed canopy mix: the patch margin, not its core.
        const ambiguity = Math.min(1, Math.max(0, 1 - Math.abs(mean - 0.13) / 0.09))
        const conf = Math.min(0.99, Math.max(0.35, 0.97 - ambiguity * 0.42 - rng() * 0.16))
        const abstained = infestPct > 6 && conf < 0.5
        const treated = !abstained && max * 100 >= thresholdPct
        total++
        if (treated) flagged++
        if (abstained) abstainedN++
        cells[idx] = { col: c, row: r, infestPct, weedClass: cls, confidence: conf, abstained, treated, inside: true }
      }
    }
    return {
      cols, rows, cellMeters: size, originX: this.minX, originY: this.minY, cells,
      total, flagged, abstained: abstainedN,
      treatedFraction: total === 0 ? 0 : flagged / total,
      treatedSqm: flagged * size * size,
    }
  }

  /**
   * Zones are the contiguous prescription area around each patch. The route is a greedy
   * nearest-neighbour chain from the field gate: what "nearest first" means to a person on foot.
   */
  zones(gate: Pt, thresholdPct: number): TreatmentZone[] {
    const thr = thresholdPct / 100
    const candidates: TreatmentZone[] = this.patches.filter((p) => p.peak >= 0.3).map((p) => {
      let count = 0, sum = 0
      const reach = Math.floor(p.radiusM * 1.4 * p.stretch) + 2
      for (let yy = -reach; yy <= reach; yy++) for (let xx = -reach; xx <= reach; xx++) {
        const x = p.cx + xx, y = p.cy + yy
        if (!contains(this.boundary, x, y)) continue
        if (this.patchValue(p, x, y) >= thr) { count++; sum += this.valueAt(x, y) }
      }
      const mean = count === 0 ? 0 : (sum / count) * 100
      return {
        id: `Z-${p.label}`, label: `Zone ${p.label}`, letter: p.label,
        severity: band(mean * 1.25), dominantClass: p.weedClass,
        areaSqm: count, cellCount: Math.round(count / 4), cx: p.cx, cy: p.cy,
        radiusM: Math.sqrt(count / Math.PI), distanceM: 0, state: 'FLAGGED' as const, meanInfestPct: mean,
      }
    }).filter((z) => z.areaSqm > 0)
    const remaining = [...candidates]
    const ordered: TreatmentZone[] = []
    let at = gate
    while (remaining.length) {
      let bi = 0, bd = Infinity
      remaining.forEach((z, i) => {
        const d = dist({ x: z.cx, y: z.cy }, at)
        if (d < bd) { bd = d; bi = i }
      })
      const next = remaining.splice(bi, 1)[0]
      ordered.push({ ...next, distanceM: Math.round(bd) })
      at = { x: next.cx, y: next.cy }
    }
    return ordered
  }
}

/** Traces the outline of the prescription so the sprayer's shape reads over the imagery. */
export function prescriptionEdges(grid: RasterGrid): Float32Array {
  const segs: number[] = []
  const cm = grid.cellMeters
  const t = (c: number, r: number) => {
    if (c < 0 || r < 0 || c >= grid.cols || r >= grid.rows) return false
    const cell = grid.cells[r * grid.cols + c]
    return cell.inside && cell.treated
  }
  for (let r = 0; r < grid.rows; r++) for (let c = 0; c < grid.cols; c++) {
    if (!t(c, r)) continue
    const x0 = grid.originX + c * cm
    const y0 = grid.originY + r * cm
    if (!t(c, r - 1)) segs.push(x0, y0, x0 + cm, y0)
    if (!t(c, r + 1)) segs.push(x0, y0 + cm, x0 + cm, y0 + cm)
    if (!t(c - 1, r)) segs.push(x0, y0, x0, y0 + cm)
    if (!t(c + 1, r)) segs.push(x0 + cm, y0, x0 + cm, y0 + cm)
  }
  return Float32Array.from(segs)
}

export function cellAt(grid: RasterGrid, x: number, y: number): GridCell | undefined {
  const c = Math.floor((x - grid.originX) / grid.cellMeters)
  const r = Math.floor((y - grid.originY) / grid.cellMeters)
  if (c < 0 || r < 0 || c >= grid.cols || r >= grid.rows) return undefined
  return grid.cells[r * grid.cols + c]
}

/** Spreadsheet-style reference for a cell: A1, B1 … Z1, AA1. */
export function cellRef(col: number, row: number): string {
  let n = col
  let s = ''
  do {
    s = String.fromCharCode(65 + (n % 26)) + s
    n = Math.floor(n / 26) - 1
  } while (n >= 0)
  return `${s}${row + 1}`
}
