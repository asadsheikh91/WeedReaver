import { useEffect, useReducer, useRef, useState } from 'react'
import { api } from '../api/client'
import type {
  GridCompareDto, GridDto, GridStatsDto, OverviewDto, ReviewCellsDto, RotationDto, SeasonCalendarDto, VerificationDto, ZoneDto,
} from '../api/dto'
import { toGrid, toZone } from './adapters'
import type { GridSize, RasterGrid, SurveyRole, TreatmentZone } from './types'

/**
 * Read-through cache for everything derived per field and per setting (grids, zone previews, verification,
 * rotation, the overview). Entries are keyed by request; mutations invalidate by key prefix. A component
 * keeps showing its previous answer while the next one loads, so dragging a slider never flashes empty.
 */

interface Entry { value?: unknown; error?: unknown; promise?: Promise<void>; at: number }
const cache = new Map<string, Entry>()
const listeners = new Set<() => void>()
const notify = () => listeners.forEach((l) => l())
const MAX = 160

function load(key: string, fn: () => Promise<unknown>) {
  const e: Entry = { at: Date.now() }
  e.promise = fn().then((v) => { e.value = v }, (err) => { e.error = err }).finally(() => { e.promise = undefined; notify() })
  cache.set(key, e)
  if (cache.size > MAX) {
    for (const [k, v] of cache) { if (!v.promise) { cache.delete(k); break } }
  }
}

/** Drop cached answers whose key starts with any prefix, and refetch the ones on screen. */
export function invalidate(...prefixes: string[]) {
  for (const k of [...cache.keys()]) if (prefixes.length === 0 || prefixes.some((p) => k.startsWith(p))) cache.delete(k)
  notify()
}

export function useQuery<T>(key: string | null, fn: () => Promise<T>): { data: T | undefined; error: unknown; loading: boolean } {
  const [, force] = useReducer((x: number) => x + 1, 0)
  const fnRef = useRef(fn)
  fnRef.current = fn
  const last = useRef<{ key: string | null; data: T | undefined }>({ key: null, data: undefined })
  useEffect(() => { listeners.add(force); return () => { listeners.delete(force) } }, [])

  const e = key ? cache.get(key) : undefined
  useEffect(() => {
    if (key && !cache.has(key)) load(key, () => fnRef.current())
  })
  if (key && e && 'value' in e && e.value !== undefined) last.current = { key, data: e.value as T }
  const fresh = !!key && !!e && e.value !== undefined
  return {
    data: key ? (fresh ? (e!.value as T) : last.current.data) : undefined,
    error: e?.error,
    loading: !!key && (!e || !!e.promise),
  }
}

/** The latest value, after it has stopped changing for `ms`. */
export function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value)
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t) }, [value, ms])
  return v
}

// ---- per-field queries --------------------------------------------------------------------------

export function useGrid(fieldId: string | undefined, size: GridSize, threshold: number, survey: SurveyRole = 'PRE', enabled = true) {
  const t = useDebounced(threshold, 140)
  const key = fieldId && enabled ? `grid/${fieldId}/${survey}/${size}/${t}` : null
  return useQuery<RasterGrid>(key, async () => toGrid(await api.get<GridDto>(`/fields/${fieldId}/grid`, { size, threshold: t, survey }))).data ?? null
}

export function useGridStats(fieldId: string | undefined, size: GridSize, threshold: number, enabled = true, survey: SurveyRole = 'PRE') {
  const key = fieldId && enabled ? `stats/${fieldId}/${survey}/${size}/${threshold}` : null
  return useQuery<GridStatsDto>(key, () => api.get(`/fields/${fieldId}/grid/stats`, { size, threshold, survey })).data ?? null
}

export function useGridCompare(fieldId: string | undefined, threshold: number, enabled = true) {
  const t = useDebounced(threshold, 140)
  const key = fieldId && enabled ? `compare/${fieldId}/${t}` : null
  return useQuery<GridCompareDto>(key, () => api.get(`/fields/${fieldId}/grid/compare`, { threshold: t })).data ?? null
}

/** Zones computed at an unpublished threshold (the slider), before they are published. */
export function useZonePreview(fieldId: string | undefined, threshold: number, enabled = true) {
  const t = useDebounced(threshold, 140)
  const key = fieldId && enabled ? `zones-preview/${fieldId}/${t}` : null
  return useQuery<TreatmentZone[]>(key, async () => (await api.get<ZoneDto[]>(`/fields/${fieldId}/zones/preview`, { threshold: t })).map(toZone)).data
}

export function useVerification(fieldId: string | undefined, role: 'PLUS_14D' | 'PLUS_28D' = 'PLUS_14D', enabled = true) {
  const key = fieldId && enabled ? `verification/${fieldId}/${role}` : null
  return useQuery<VerificationDto>(key, () => api.get(`/fields/${fieldId}/verification`, { role }))
}

export function useRotation(fieldId: string | undefined) {
  return useQuery<RotationDto>(fieldId ? `rotation/${fieldId}` : null, () => api.get(`/fields/${fieldId}/rotation`))
}

export const useOverview = () => useQuery<OverviewDto>('overview', () => api.get('/overview'))
export const useSeasonCalendar = () => useQuery<SeasonCalendarDto>('season', () => api.get('/season'))
export const useReviewCells = () => useQuery<ReviewCellsDto[]>('review-cells', () => api.get('/review/cells', { size: 2 }))
