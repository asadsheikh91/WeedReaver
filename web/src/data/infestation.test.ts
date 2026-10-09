import { describe, expect, it } from 'vitest'
import { InfestationField, cellRef } from './infestation'
import { area, kanalMarla } from './geo'
import { toGrid } from './adapters'
import type { Patch, Pt } from './types'
import backendGrid from './__fixtures__/f047-grid-5m.json'

// The demo parcels and patches, as seeded on the backend (backend/app/seed/demo.py).
const pts = (xy: number[][]): Pt[] => xy.map(([x, y]) => ({ x, y }))
const P = (cx: number, cy: number, radiusM: number, peak: number, weedClass: Patch['weedClass'], label: string, stretch = 1.35): Patch =>
  ({ cx, cy, radiusM, peak, weedClass, label, stretch })
const CHAK_47 = pts([[22, 0], [243, 0], [245, 142], [1, 143], [0, 24], [22, 24]])
const CHAK_47_PATCHES = [
  P(60, 44, 22, 0.86, 'GRASS', 'A', 1.45), P(152, 50, 17, 0.62, 'GRASS', 'B', 1.5), P(204, 106, 19, 0.72, 'BROADLEAF', 'C', 1.1),
  P(100, 108, 15, 0.46, 'BROADLEAF', 'D', 1.15), P(28, 100, 11, 0.36, 'GRASS', 'E', 1.6), P(126, 14, 9, 0.22, 'GRASS', 'F', 1.8),
]
const chak47 = () => new InfestationField(CHAK_47, CHAK_47_PATCHES, 4711)

describe('parcel geometry', () => {
  it('computes Chak 47 as 8.45 acres from its vertices', () => {
    expect((area(CHAK_47) / 4046.86).toFixed(2)).toBe('8.45')
  })

  it('converts acres to kanal and marla, carrying a rounded 20 marla into a kanal', () => {
    expect(kanalMarla(8.446919339932688)).toEqual({ kanal: 67, marla: 12 })
    expect(kanalMarla(1 - 0.001)).toEqual({ kanal: 8, marla: 0 })
  })
})

describe('InfestationField.rasterise', () => {
  // The same reference figures the backend's test_analysis.py asserts.
  it.each([
    [1, 245, 143, 34183, 2441, 217],
    [2, 123, 72, 8530, 688, 43],
    [5, 49, 29, 1377, 135, 12],
  ] as const)('a %i m grid of Chak 47 has the agreed size, flagged and abstained counts', (size, cols, rows, total, flagged, abstained) => {
    const g = chak47().rasterise(size, 10)
    expect([g.cols, g.rows, g.total, g.flagged, g.abstained]).toEqual([cols, rows, total, flagged, abstained])
  })

  it('flags more area as the grid coarsens, because any part of a cell crossing the threshold counts', () => {
    const f = chak47()
    const [a, b, c] = ([1, 2, 5] as const).map((s) => f.rasterise(s, 10).treatedFraction)
    expect(a).toBeLessThan(b)
    expect(b).toBeLessThan(c)
  })

  it('never sprays an abstained cell', () => {
    const g = chak47().rasterise(2, 10)
    expect(g.cells.some((c) => c.abstained && c.treated)).toBe(false)
  })
})

describe('InfestationField.zones', () => {
  it('orders zones nearest-first from the gate', () => {
    const zones = chak47().zones({ x: 1, y: 84 }, 10)
    expect(zones.map((z) => [z.letter, z.areaSqm, z.distanceM])).toEqual([['E', 138, 31], ['A', 988, 64], ['D', 238, 75], ['B', 502, 78], ['C', 503, 76]])
  })
})

describe('the backend grid contract', () => {
  it('decodes a real backend grid response into exactly the cells the web engine computes', () => {
    const decoded = toGrid(backendGrid as Parameters<typeof toGrid>[0])
    const local = chak47().rasterise(5, 10)
    expect([decoded.cols, decoded.rows, decoded.flagged, decoded.abstained]).toEqual([local.cols, local.rows, local.flagged, local.abstained])
    decoded.cells.forEach((c, i) => {
      const w = local.cells[i]
      expect([c.col, c.row, c.inside, c.treated, c.abstained, c.weedClass], cellRef(c.col, c.row)).toEqual([w.col, w.row, w.inside, w.treated, w.abstained, w.weedClass])
      expect(Math.abs(c.infestPct - w.infestPct)).toBeLessThanOrEqual(0.05) // the API rounds to 0.1
      expect(Math.abs(c.confidence - w.confidence)).toBeLessThanOrEqual(0.005) // and to 0.01
    })
  })
})

describe('cellRef', () => {
  it('names cells like a spreadsheet', () => {
    expect([cellRef(0, 0), cellRef(25, 4), cellRef(26, 0)]).toEqual(['A1', 'Z5', 'AA1'])
  })
})
