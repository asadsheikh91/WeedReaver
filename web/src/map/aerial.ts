import type { FieldParcel } from '../data/types'
import { makeSpec, specSize, type AerialSpec } from './aerialCore'

/**
 * Coordinates the imagery renderer. The base land-cover pass is split into row bands and fanned out
 * across a worker pool; one worker then draws the vector layer and grades the frame. Results are cached
 * per field so every screen that shows a field shares one bitmap.
 */

export interface Imagery { spec: AerialSpec; bitmap: ImageBitmap; w: number; h: number }

const cache = new Map<string, Promise<Imagery>>()
const ready = new Map<string, Imagery>()
const listeners = new Set<() => void>()

let pool: Worker[] = []
let nextId = 1
const pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>()

function ensurePool() {
  if (pool.length) return
  const n = Math.min(6, Math.max(2, (navigator.hardwareConcurrency || 4) - 1))
  pool = Array.from({ length: n }, () => {
    const w = new Worker(new URL('./aerial.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e) => {
      const m = e.data
      const p = pending.get(m.id)
      if (!p) return
      pending.delete(m.id)
      if (m.kind === 'error') p.reject(new Error(m.message)); else p.resolve(m)
    }
    return w
  })
}

function call<T>(worker: Worker, msg: Record<string, unknown>, transfer: Transferable[] = []): Promise<T> {
  const id = nextId++
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve, reject })
    worker.postMessage({ id, ...msg }, transfer)
  })
}

async function render(spec: AerialSpec): Promise<Imagery> {
  ensurePool()
  const t0 = performance.now()
  const { w, h } = specSize(spec)
  const full = new Uint8ClampedArray(w * h * 4)
  const bandRows = Math.max(24, Math.ceil(h / (pool.length * 3)))
  const bands: [number, number][] = []
  for (let y = 0; y < h; y += bandRows) bands.push([y, Math.min(h, y + bandRows)])

  let next = 0
  await Promise.all(pool.map(async (worker) => {
    while (next < bands.length) {
      const [y0, y1] = bands[next++]
      const r = await call<{ y0: number; pixels: Uint8ClampedArray }>(worker, { kind: 'band', spec, y0, y1 })
      full.set(r.pixels, r.y0 * w * 4)
    }
  }))

  const t1 = performance.now()
  const fin = await call<{ bitmap: ImageBitmap }>(pool[0], { kind: 'finish', spec, base: full.buffer }, [full.buffer])
  console.debug(`[imagery] ${spec.key} ${w}x${h} bands ${Math.round(t1 - t0)} ms, finish ${Math.round(performance.now() - t1)} ms`)
  const img: Imagery = { spec, bitmap: fin.bitmap, w, h }
  ready.set(spec.key, img)
  listeners.forEach((l) => l())
  return img
}

export function loadImagery(spec: AerialSpec): Promise<Imagery> {
  let p = cache.get(spec.key)
  if (!p) {
    p = render(spec)
    cache.set(spec.key, p)
    p.catch(() => cache.delete(spec.key))
  }
  return p
}

export const cachedImagery = (key: string) => ready.get(key)
export const onImageryReady = (fn: () => void) => { listeners.add(fn); return () => { listeners.delete(fn) } }

const specs = new Map<string, AerialSpec>()
/** Stable imagery spec for a parcel. Includes the weed patches (from its zones) only if a survey has been processed. */
export function specFor(f: FieldParcel, surveyed: boolean): AerialSpec {
  const patches = surveyed ? f.patches ?? [] : null
  const sig = patches ? patches.map((p) => `${p.label}${Math.round(p.cx)},${Math.round(p.cy)},${Math.round(p.radiusM)},${Math.round(p.peak * 100)}`).join(';') : 'n'
  const key = `${f.id}:${f.boundary.length}:${Math.round(f.boundary[0].x)}:${sig}`
  let s = specs.get(key)
  if (!s) {
    s = makeSpec(key, f.boundary, f.landscape, patches, f.landscape.seed)
    specs.set(key, s)
  }
  return s
}

/** Kick off rendering for every parcel so maps are ready before anyone opens them. */
export function warmImagery(fields: FieldParcel[], surveyedIds: Set<string>) {
  fields.forEach((f, i) => setTimeout(() => void loadImagery(specFor(f, surveyedIds.has(f.id))), i * 40))
}
