import { InfestationField } from './infestation'
import { SHOWCASE_EFFICACY as efficacyFactors } from './showcase'
import type { FieldParcel, GridSize, RasterGrid, TreatmentZone } from './types'

/**
 * Client-side analysis for the public project page's worked example only (see showcase.ts). The dashboard
 * itself gets grids and zones from the API, which runs the same algorithms on the station server.
 */

const fieldCache = new Map<string, InfestationField>()
export function infestationOf(f: FieldParcel): InfestationField {
  const key = `${f.id}:${f.boundary.length}:${f.boundary[0].x}`
  let v = fieldCache.get(key)
  if (!v) {
    v = new InfestationField(f.boundary, f.patches ?? [], f.landscape.seed)
    fieldCache.set(key, v)
  }
  return v
}

const gridCache = new Map<string, RasterGrid>()
function memo<T>(cache: Map<string, T>, key: string, make: () => T, max = 48): T {
  const hit = cache.get(key)
  if (hit) { cache.delete(key); cache.set(key, hit); return hit }
  const v = make()
  cache.set(key, v)
  if (cache.size > max) cache.delete(cache.keys().next().value as string)
  return v
}

export const gridOf = (f: FieldParcel, size: GridSize, threshold: number): RasterGrid =>
  memo(gridCache, `${f.id}/${size}/${threshold}`, () => infestationOf(f).rasterise(size, threshold))

/** The +14 d (or +28 d) survey: every patch peak scaled by what the treatment achieved in that zone. */
export const followUpGridOf = (f: FieldParcel, size: GridSize, threshold: number, late = false): RasterGrid =>
  memo(gridCache, `${f.id}/${size}/${threshold}/${late ? 28 : 14}`, () => {
    const factors = Object.fromEntries(Object.entries(efficacyFactors).map(([k, v]) => [k, late ? v * 0.85 : v]))
    return infestationOf(f).scaled(factors, 0.5).rasterise(size, threshold)
  })

const zoneCache = new Map<string, TreatmentZone[]>()
export const zonesOf = (f: FieldParcel, threshold: number): TreatmentZone[] =>
  memo(zoneCache, `${f.id}/${threshold}`, () => infestationOf(f).zones(f.gate, threshold))

