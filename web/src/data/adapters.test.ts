import { describe, expect, it } from 'vitest'
import { toGrid, toZone } from './adapters'
import { ms } from './clock'
import type { GridDto, ZoneDto } from '../api/dto'

describe('ms', () => {
  it('reads ISO-8601 UTC timestamps as epoch milliseconds', () => {
    expect(ms('2027-01-22T05:30:00Z')).toBe(Date.UTC(2027, 0, 22, 5, 30))
  })

  it('reads a date-only string as local midnight, not UTC midnight', () => {
    expect(ms('2026-11-12')).toBe(new Date(2026, 10, 12).getTime())
  })

  it('passes null through', () => {
    expect(ms(null)).toBeNull()
  })
})

describe('toGrid', () => {
  it('decodes the flag bits and class letters of the columnar encoding', () => {
    const dto = {
      cols: 2, rows: 1, cellMeters: 2, originX: 0, originY: 0,
      infestPct: [0, 42.5], confidence: [1, 0.4], classes: 'CB', flags: [0, 1 | 4],
      stats: { total: 1, flagged: 0, abstained: 1, treatedFraction: 0, treatedSqm: 0 },
    } as unknown as GridDto
    const g = toGrid(dto)
    expect(g.cells[0]).toMatchObject({ col: 0, inside: false, treated: false, abstained: false, weedClass: 'CROP' })
    expect(g.cells[1]).toMatchObject({ col: 1, row: 0, inside: true, treated: false, abstained: true, weedClass: 'BROADLEAF', infestPct: 42.5 })
  })
})

describe('toZone', () => {
  it('keys a zone by its code and defaults a missing state to flagged', () => {
    const z = toZone({
      code: 'Z-A', label: 'Zone A', letter: 'A', severity: 'HEAVY', dominantClass: 'GRASS', areaSqm: 988, cellCount: 247,
      cx: 60, cy: 44, radiusM: 17.7, distanceM: 64, meanInfestPct: 29.6, state: undefined, efficacyPct: null, treatedAt: null,
    } as unknown as ZoneDto)
    expect([z.id, z.state, z.efficacyPct, z.treatedAt]).toEqual(['Z-A', 'FLAGGED', undefined, undefined])
  })
})
