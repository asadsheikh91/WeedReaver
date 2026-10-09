import { Maximize2, Minus, Plus } from 'lucide-react'
import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type ReactNode, type Ref } from 'react'
import type { Bounds } from '../data/geo'
import { bounds, toLatLon } from '../data/geo'
import type { FieldParcel, Pt } from '../data/types'
import { cachedImagery, loadImagery, onImageryReady, specFor } from './aerial'
import { Camera, easeEmphasized, NO_INSETS, type Insets } from './camera'
import type { DrawCtx } from './overlays'
import './map.css'

export interface MapHandle {
  camera: Camera
  fit(animate?: boolean): void
  flyTo(p: Pt, zoom?: number, ms?: number): void
  zoomBy(factor: number): void
  invalidate(): void
}

interface Props {
  field: FieldParcel
  surveyed: boolean
  ref?: Ref<MapHandle>
  className?: string
  insets?: Insets
  fitPad?: number
  fitBounds?: Bounds
  interactive?: boolean
  controls?: boolean
  coords?: boolean
  scaleBar?: boolean
  animated?: boolean
  drift?: boolean
  cursor?: string
  draw?: (dc: DrawCtx) => void
  onHover?: (pt: Pt | null, screen: { x: number; y: number }) => void
  onClick?: (pt: Pt, screen: { x: number; y: number }) => void
  children?: ReactNode
}

const NICE = [1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000]

export function MapView({
  field, surveyed, ref, className = '', insets = NO_INSETS, fitPad = 32, fitBounds, interactive = true, controls = true,
  coords = false, scaleBar = true, animated = false, drift = false, cursor, draw, onHover, onClick, children,
}: Props) {
  const wrap = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const cam = useRef(new Camera()).current
  const spec = useMemo(() => specFor(field, surveyed), [field, surveyed])
  if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__cam = cam
  const [zoomShown, setZoomShown] = useState(0)
  const [hover, setHover] = useState<Pt | null>(null)
  const raf = useRef(0)
  const start = useRef(performance.now())
  const fadeAt = useRef<number | null>(null)
  const anim = useRef<{ fx: number; fy: number; fz: number; tx: number; ty: number; tz: number; t0: number; ms: number } | null>(null)
  const props = useRef({ draw, onHover, onClick, animated, fitBounds, fitPad, insets })
  props.current = { draw, onHover, onClick, animated, fitBounds, fitPad, insets }

  const fb = useMemo<Bounds>(() => fitBounds ?? bounds(field.boundary), [fitBounds, field])

  const frameRef = useRef<() => void>(() => {})
  const zoomRef = useRef(0)
  const schedule = useCallback(() => {
    if (raf.current) return
    raf.current = requestAnimationFrame(() => { raf.current = 0; frameRef.current() })
  }, [])

  const frame = useCallback(() => {
    const cv = canvas.current
    if (!cv || !cam.ready) return
    const dpr = window.devicePixelRatio || 1
    const ctx = cv.getContext('2d')!
    const now = performance.now()

    let busy = props.current.animated
    const a = anim.current
    if (a) {
      const k = Math.min(1, (now - a.t0) / a.ms)
      const e = easeEmphasized(k)
      cam.cx = a.fx + (a.tx - a.fx) * e
      cam.cy = a.fy + (a.ty - a.fy) * e
      cam.zoom = a.fz * Math.pow(a.tz / a.fz, e)
      if (k >= 1) anim.current = null; else busy = true
    }

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.fillStyle = '#B3AE95'
    ctx.fillRect(0, 0, cam.width, cam.height)

    const img = cachedImagery(spec.key)
    if (img) {
      if (fadeAt.current === null) fadeAt.current = now
      const f = Math.min(1, (now - fadeAt.current) / 520)
      if (f < 1) busy = true
      ctx.globalAlpha = f
      ctx.imageSmoothingEnabled = true
      ctx.imageSmoothingQuality = 'high'
      ctx.drawImage(img.bitmap, cam.sx(spec.minX), cam.sy(spec.minY), (spec.maxX - spec.minX) * cam.zoom, (spec.maxY - spec.minY) * cam.zoom)
      ctx.globalAlpha = 1
    } else {
      // tile lattice, the way a real map shows tiles that have not arrived yet
      ctx.strokeStyle = 'rgba(255,255,255,0.14)'; ctx.lineWidth = 1
      const step = 64 * Math.max(1, cam.zoom / 4)
      ctx.beginPath()
      for (let x = ((cam.width / 2) % step); x < cam.width; x += step) { ctx.moveTo(Math.round(x) + 0.5, 0); ctx.lineTo(Math.round(x) + 0.5, cam.height) }
      for (let y = ((cam.height / 2) % step); y < cam.height; y += step) { ctx.moveTo(0, Math.round(y) + 0.5); ctx.lineTo(cam.width, Math.round(y) + 0.5) }
      ctx.stroke()
    }
    props.current.draw?.({ ctx, cam, t: now - start.current, w: cam.width, h: cam.height })
    if (Math.abs(cam.zoom - zoomRef.current) / cam.zoom > 0.004) { zoomRef.current = cam.zoom; setZoomShown(cam.zoom) }
    if (busy) schedule()
  }, [cam, spec, schedule])
  frameRef.current = frame

  // ---- sizing --------------------------------------------------------------------------------
  useEffect(() => {
    const el = wrap.current!, cv = canvas.current!
    const resize = () => {
      const r = el.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      const first = cam.width === 0
      cam.width = Math.max(1, r.width); cam.height = Math.max(1, r.height)
      cv.width = Math.round(r.width * dpr); cv.height = Math.round(r.height * dpr)
      cam.insets = props.current.insets
      cam.world = [spec.minX, spec.minY, spec.maxX, spec.maxY]
      cam.minZoom = Math.min(cam.width / (spec.maxX - spec.minX), cam.height / (spec.maxY - spec.minY))
      cam.maxZoom = 12
      if (first || !cam.userMoved) cam.snapTo(fb, props.current.fitPad)
      schedule()
    }
    const ro = new ResizeObserver(resize)
    ro.observe(el)
    resize()
    return () => ro.disconnect()
  }, [cam, spec, fb, schedule])

  // camera insets can change when a panel opens
  useEffect(() => {
    cam.insets = insets
    if (cam.ready && !cam.userMoved) cam.snapTo(fb, fitPad)
    schedule()
  }, [cam, insets.top, insets.right, insets.bottom, insets.left, fb, fitPad, schedule]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- imagery -------------------------------------------------------------------------------
  const [loading, setLoading] = useState(!cachedImagery(spec.key))
  useEffect(() => {
    fadeAt.current = null
    setLoading(!cachedImagery(spec.key))
    void loadImagery(spec).then(() => { setLoading(false); schedule() })
    return onImageryReady(() => { setLoading(!cachedImagery(spec.key)); schedule() })
  }, [spec, schedule])

  // repaint whenever the parent re-renders with new overlay data
  useEffect(() => { schedule() })
  useEffect(() => () => { if (raf.current) { cancelAnimationFrame(raf.current); raf.current = 0 } }, [])

  // ---- imperative api ------------------------------------------------------------------------
  const flyTo = useCallback((p: Pt, zoom?: number, ms = 650) => {
    anim.current = { fx: cam.cx, fy: cam.cy, fz: cam.zoom, tx: p.x, ty: p.y, tz: Math.min(cam.maxZoom, Math.max(cam.minZoom, zoom ?? cam.zoom)), t0: performance.now(), ms }
    schedule()
  }, [cam, schedule])
  const fit = useCallback((animate = true) => {
    cam.userMoved = false
    const z = cam.fitZoom(fb, props.current.fitPad)
    const c = { x: (fb[0] + fb[2]) / 2, y: (fb[1] + fb[3]) / 2 }
    if (animate) flyTo(c, z); else { cam.snapTo(fb, props.current.fitPad); schedule() }
  }, [cam, fb, flyTo, schedule])
  const zoomBy = useCallback((factor: number) => {
    const tz = Math.min(cam.maxZoom, Math.max(cam.minZoom, cam.zoom * factor))
    cam.userMoved = true
    anim.current = { fx: cam.cx, fy: cam.cy, fz: cam.zoom, tx: cam.cx, ty: cam.cy, tz, t0: performance.now(), ms: 260 }
    schedule()
  }, [cam, schedule])
  useImperativeHandle(ref, () => ({ camera: cam, fit, flyTo, zoomBy, invalidate: schedule }), [cam, fit, flyTo, zoomBy, schedule])

  // ---- drift (landing hero) -------------------------------------------------------------------
  useEffect(() => {
    if (!drift) return
    const framings: [number, number, number][] = [[0, 0, 1.0], [30, -14, 1.14], [-34, 12, 1.06], [12, 26, 1.18], [-14, -20, 1.02]]
    let i = 0, alive = true, timer = 0
    const step = () => {
      if (!alive) return
      if (cam.ready) {
        const base = { x: (fb[0] + fb[2]) / 2, y: (fb[1] + fb[3]) / 2 }
        const z0 = cam.fitZoom(fb, props.current.fitPad)
        const [dx, dy, k] = framings[i++ % framings.length]
        flyTo({ x: base.x + dx, y: base.y + dy }, z0 * k, 12000)
      }
      timer = window.setTimeout(step, 12000)
    }
    timer = window.setTimeout(step, 1200)
    return () => { alive = false; clearTimeout(timer) }
  }, [drift, cam, fb, flyTo])

  // ---- pointer handling ----------------------------------------------------------------------
  useEffect(() => {
    const el = canvas.current!
    if (!interactive) return
    const pts = new Map<number, { x: number; y: number }>()
    let moved = 0, last = 0

    const local = (e: { clientX: number; clientY: number }) => {
      const r = el.getBoundingClientRect()
      return { x: e.clientX - r.left, y: e.clientY - r.top }
    }
    const down = (e: PointerEvent) => {
      el.setPointerCapture(e.pointerId)
      pts.set(e.pointerId, local(e)); moved = 0; anim.current = null
      if (pts.size === 2) last = dist2()
    }
    const dist2 = () => { const [a, b] = [...pts.values()]; return Math.hypot(a.x - b.x, a.y - b.y) }
    const move = (e: PointerEvent) => {
      const p = local(e)
      if (pts.has(e.pointerId)) {
        const prev = pts.get(e.pointerId)!
        pts.set(e.pointerId, p)
        if (pts.size === 1) {
          moved += Math.abs(p.x - prev.x) + Math.abs(p.y - prev.y)
          if (moved > 4) { cam.panBy(p.x - prev.x, p.y - prev.y); schedule() }
        } else if (pts.size === 2) {
          const d = dist2()
          const [a, b] = [...pts.values()]
          cam.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, d / (last || d)); last = d; moved = 99; schedule()
        }
      } else {
        const w = cam.unproject(p.x, p.y)
        setHover(w); props.current.onHover?.(w, p)
      }
    }
    const up = (e: PointerEvent) => {
      const p = local(e)
      const wasSingle = pts.size === 1
      pts.delete(e.pointerId)
      if (wasSingle && moved <= 4) props.current.onClick?.(cam.unproject(p.x, p.y), p)
    }
    const leave = () => { setHover(null); props.current.onHover?.(null, { x: 0, y: 0 }) }
    const wheel = (e: WheelEvent) => {
      e.preventDefault()
      anim.current = null
      const p = local(e)
      cam.zoomAt(p.x, p.y, Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0016)))
      schedule()
    }
    const dbl = (e: MouseEvent) => {
      const p = local(e)
      const target = cam.zoom * 2
      const ax = cam.wx(p.x), ay = cam.wy(p.y)
      const z0 = cam.zoom
      cam.zoom = Math.min(cam.maxZoom, target)
      const nx = cam.cx + ax - cam.wx(p.x), ny = cam.cy + ay - cam.wy(p.y)
      const z1 = cam.zoom
      cam.zoom = z0
      cam.userMoved = true
      anim.current = { fx: cam.cx, fy: cam.cy, fz: z0, tx: nx, ty: ny, tz: z1, t0: performance.now(), ms: 320 }
      schedule()
    }
    el.addEventListener('pointerdown', down)
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', up)
    el.addEventListener('pointerleave', leave)
    el.addEventListener('wheel', wheel, { passive: false })
    el.addEventListener('dblclick', dbl)
    return () => {
      el.removeEventListener('pointerdown', down); el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up); el.removeEventListener('pointercancel', up)
      el.removeEventListener('pointerleave', leave); el.removeEventListener('wheel', wheel)
      el.removeEventListener('dblclick', dbl)
    }
  }, [cam, interactive, schedule])

  // ---- scale bar -----------------------------------------------------------------------------
  const scale = useMemo(() => {
    if (!zoomShown) return null
    const maxPx = 96
    const m = [...NICE].reverse().find((n) => n * zoomShown <= maxPx) ?? 1
    return { m, px: m * zoomShown }
  }, [zoomShown])

  const ll = hover ? toLatLon(field, hover) : null

  return (
    <div ref={wrap} className={`map ${className}`} style={{ cursor: cursor ?? (interactive ? 'grab' : 'default') }}>
      <canvas ref={canvas} className="map__canvas" />
      {loading && (
        <div className="map__loading"><span className="map__spinner" />Rendering survey imagery</div>
      )}
      {children}
      {controls && interactive && (
        <div className="map__controls" style={{ right: 12 + insets.right, top: 12 + insets.top }}>
          <button aria-label="Zoom in" onClick={() => zoomBy(1.6)}><Plus size={17} /></button>
          <button aria-label="Zoom out" onClick={() => zoomBy(1 / 1.6)}><Minus size={17} /></button>
          <button aria-label="Fit field" onClick={() => fit(true)}><Maximize2 size={15} /></button>
        </div>
      )}
      {(scaleBar || coords) && (
        <div className="map__foot" style={{ left: 12 + insets.left, bottom: 10 + insets.bottom }}>
          {scaleBar && scale && (
            <div className="map__scale">
              <span>{scale.m >= 1000 ? `${scale.m / 1000} km` : `${scale.m} m`}</span>
              <i style={{ width: scale.px }} />
            </div>
          )}
          {coords && (
            <div className="map__coords">{ll ? `${ll[0].toFixed(5)}° N  ${ll[1].toFixed(5)}° E` : `${field.lat.toFixed(5)}° N  ${field.lon.toFixed(5)}° E`}</div>
          )}
        </div>
      )}
    </div>
  )
}
