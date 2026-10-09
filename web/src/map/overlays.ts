import type { GridCell, Pt, RasterGrid, TreatmentZone } from '../data/types'
import type { Camera } from './camera'

export interface DrawCtx {
  ctx: CanvasRenderingContext2D
  cam: Camera
  /** milliseconds since the map mounted, for pulses and dashes */
  t: number
  w: number
  h: number
}

export const COLORS = {
  heatLow: '#9BD08A', heatMid: '#F3C04A', heatHigh: '#E4552D', heatAbstain: '#A7A3C4',
  forest: '#1E4A33', moss: '#4F8B5C', wheat: '#C9983D', clay: '#B9552F', ink: '#1B2A21',
  puck: '#2F7FD8', route: '#FFF6DE', cream: '#F5F0E6', ivory: '#FFFCF6',
}

export function pathOf(dc: DrawCtx, poly: Pt[], close = true) {
  const { ctx, cam } = dc
  ctx.beginPath()
  poly.forEach((p, i) => {
    const x = cam.sx(p.x), y = cam.sy(p.y)
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
  })
  if (close && poly.length > 2) ctx.closePath()
}

/** Parcel outline: a soft dark halo under a crisp light line reads on any imagery. */
export function fieldOutline(
  dc: DrawCtx, poly: Pt[],
  o: { color?: string; width?: number; fill?: string; dashed?: boolean; close?: boolean } = {},
) {
  if (poly.length < 2) return
  const { ctx } = dc
  const w = o.width ?? 2.2
  pathOf(dc, poly, o.close ?? true)
  if (o.fill) { ctx.fillStyle = o.fill; ctx.fill() }
  ctx.lineJoin = 'round'; ctx.lineCap = 'round'
  ctx.setLineDash([])
  ctx.strokeStyle = 'rgba(0,0,0,0.28)'; ctx.lineWidth = w + 3; ctx.stroke()
  ctx.strokeStyle = o.color ?? '#fff'; ctx.lineWidth = w
  if (o.dashed) ctx.setLineDash([10, 6])
  ctx.stroke()
  ctx.setLineDash([])
}

/** Dims everything outside the parcel so the working area carries the eye. */
export function dimOutside(dc: DrawCtx, poly: Pt[], alpha = 0.26) {
  const { ctx, w, h } = dc
  ctx.save()
  ctx.beginPath()
  ctx.rect(0, 0, w, h)
  poly.forEach((p, i) => {
    const x = dc.cam.sx(p.x), y = dc.cam.sy(p.y)
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
  })
  ctx.closePath()
  ctx.fillStyle = `rgba(14,26,18,${alpha})`
  ctx.fill('evenodd')
  ctx.restore()
}

// ---- heat grid ------------------------------------------------------------------------------

const heatCache = new WeakMap<RasterGrid, HTMLCanvasElement>()
function heatColor(c: GridCell): [number, number, number, number] {
  if (!c.inside) return [0, 0, 0, 0]
  if (c.abstained) return [0xa7, 0xa3, 0xc4, 190]
  if (c.infestPct < 10) return [0, 0, 0, 0]
  if (c.infestPct <= 30) return [0xf3, 0xc0, 0x4a, 175]
  return [0xe4, 0x55, 0x2d, 200]
}

/** One texel per spray cell; drawn with nearest-neighbour sampling so cells stay crisp at any zoom. */
export function heatBitmap(grid: RasterGrid): HTMLCanvasElement {
  let cv = heatCache.get(grid)
  if (cv) return cv
  cv = document.createElement('canvas')
  cv.width = grid.cols; cv.height = grid.rows
  const g = cv.getContext('2d')!
  const img = g.createImageData(grid.cols, grid.rows)
  for (const cell of grid.cells) {
    const [r, gg, b, a] = heatColor(cell)
    const i = (cell.row * grid.cols + cell.col) * 4
    img.data[i] = r; img.data[i + 1] = gg; img.data[i + 2] = b; img.data[i + 3] = a
  }
  g.putImageData(img, 0, 0)
  heatCache.set(grid, cv)
  return cv
}

export function heatLayer(dc: DrawCtx, grid: RasterGrid, poly: Pt[], alpha = 1, lattice = true) {
  const { ctx, cam } = dc
  const bmp = heatBitmap(grid)
  const x0 = cam.sx(grid.originX), y0 = cam.sy(grid.originY)
  const cellPx = grid.cellMeters * cam.zoom
  ctx.save()
  pathOf(dc, poly); ctx.clip()
  ctx.globalAlpha = alpha
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(bmp, x0, y0, grid.cols * cellPx, grid.rows * cellPx)
  ctx.globalAlpha = 1
  if (lattice && cellPx > 16) {
    ctx.strokeStyle = `rgba(255,255,255,${Math.min(0.26, (cellPx - 16) / 60)})`
    ctx.lineWidth = 1
    ctx.beginPath()
    const a = cam.unproject(0, 0), b = cam.unproject(dc.w, dc.h)
    const c0 = Math.max(0, Math.floor((a.x - grid.originX) / grid.cellMeters)), c1 = Math.min(grid.cols, Math.ceil((b.x - grid.originX) / grid.cellMeters))
    const r0 = Math.max(0, Math.floor((a.y - grid.originY) / grid.cellMeters)), r1 = Math.min(grid.rows, Math.ceil((b.y - grid.originY) / grid.cellMeters))
    for (let c = c0; c <= c1; c++) { const x = Math.round(x0 + c * cellPx) + 0.5; ctx.moveTo(x, 0); ctx.lineTo(x, dc.h) }
    for (let r = r0; r <= r1; r++) { const y = Math.round(y0 + r * cellPx) + 0.5; ctx.moveTo(0, y); ctx.lineTo(dc.w, y) }
    ctx.stroke()
  }
  ctx.restore()
}

export function prescriptionOutline(dc: DrawCtx, edges: Float32Array, alpha = 0.9, color = '#fff') {
  if (!edges.length) return
  const { ctx, cam } = dc
  ctx.beginPath()
  for (let i = 0; i + 3 < edges.length; i += 4) {
    ctx.moveTo(cam.sx(edges[i]), cam.sy(edges[i + 1]))
    ctx.lineTo(cam.sx(edges[i + 2]), cam.sy(edges[i + 3]))
  }
  ctx.lineCap = 'square'
  ctx.strokeStyle = `rgba(0,0,0,${0.22 * alpha})`; ctx.lineWidth = 3; ctx.stroke()
  ctx.strokeStyle = color; ctx.globalAlpha = alpha; ctx.lineWidth = 1.3; ctx.stroke()
  ctx.globalAlpha = 1
}

export function cellHighlight(dc: DrawCtx, grid: RasterGrid, cell: GridCell) {
  const { ctx, cam } = dc
  const s = grid.cellMeters * cam.zoom
  const x = cam.sx(grid.originX + cell.col * grid.cellMeters) - 2
  const y = cam.sy(grid.originY + cell.row * grid.cellMeters) - 2
  ctx.strokeStyle = 'rgba(0,0,0,0.4)'; ctx.lineWidth = 5; ctx.strokeRect(x, y, s + 4, s + 4)
  ctx.strokeStyle = '#fff'; ctx.lineWidth = 2.4; ctx.strokeRect(x, y, s + 4, s + 4)
}

// ---- pins, routes, position -----------------------------------------------------------------

export function severityFill(z: Pick<TreatmentZone, 'severity' | 'state'>) {
  if (z.state === 'TREATED' || z.state === 'RESURVEYED') return COLORS.forest
  return z.severity === 'HEAVY' ? COLORS.heatHigh : z.severity === 'MODERATE' ? COLORS.heatMid : COLORS.heatLow
}

export function zonePin(dc: DrawCtx, z: TreatmentZone, o: { size?: number; active?: boolean; label?: string; hover?: boolean } = {}) {
  const { ctx, cam } = dc
  const x = cam.sx(z.cx), y = cam.sy(z.cy)
  const s = (o.size ?? 26) * (o.hover ? 1.12 : 1)
  const fill = severityFill(z)
  if (o.active) {
    const p = (dc.t % 1600) / 1600
    ctx.beginPath(); ctx.arc(x, y, s * (0.5 + p * 0.9), 0, Math.PI * 2)
    ctx.fillStyle = fill; ctx.globalAlpha = (1 - p) * 0.45; ctx.fill(); ctx.globalAlpha = 1
  }
  ctx.save()
  ctx.shadowColor = 'rgba(0,0,0,0.45)'; ctx.shadowBlur = 8; ctx.shadowOffsetY = 2
  ctx.beginPath(); ctx.arc(x, y, s / 2, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill()
  ctx.restore()
  ctx.beginPath(); ctx.arc(x, y, s / 2 - 2, 0, Math.PI * 2); ctx.fillStyle = fill; ctx.fill()
  ctx.fillStyle = z.severity === 'MODERATE' && fill !== COLORS.forest ? COLORS.ink : '#fff'
  ctx.font = `700 ${Math.round(s * 0.44)}px Inter Variable, Inter, system-ui, sans-serif`
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
  if (z.state === 'TREATED' || z.state === 'RESURVEYED') {
    ctx.strokeStyle = '#fff'; ctx.lineWidth = 2.2; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
    ctx.beginPath(); ctx.moveTo(x - s * 0.17, y + s * 0.01); ctx.lineTo(x - s * 0.04, y + s * 0.14); ctx.lineTo(x + s * 0.2, y - s * 0.12); ctx.stroke()
  } else ctx.fillText(o.label ?? z.letter, x, y + 0.5)
}

export function pinHit(dc: Pick<DrawCtx, 'cam'>, zones: TreatmentZone[], sx: number, sy: number, size = 26): TreatmentZone | undefined {
  for (const z of zones) {
    if (Math.hypot(dc.cam.sx(z.cx) - sx, dc.cam.sy(z.cy) - sy) <= size / 2 + 4) return z
  }
  return undefined
}

export function routeLine(dc: DrawCtx, pts: Pt[], doneLegs = 0) {
  if (pts.length < 2) return
  const { ctx, cam } = dc
  ctx.lineCap = 'round'; ctx.lineJoin = 'round'
  for (let i = 0; i < pts.length - 1; i++) {
    const ax = cam.sx(pts[i].x), ay = cam.sy(pts[i].y), bx = cam.sx(pts[i + 1].x), by = cam.sy(pts[i + 1].y)
    const done = i < doneLegs
    ctx.setLineDash([]); ctx.strokeStyle = 'rgba(0,0,0,0.32)'; ctx.lineWidth = 7
    ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke()
    if (done) {
      ctx.strokeStyle = 'rgba(255,246,222,0.55)'; ctx.lineWidth = 4
      ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke()
    } else {
      ctx.strokeStyle = 'rgba(30,74,51,0.95)'; ctx.lineWidth = 4
      ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke()
      ctx.setLineDash([9, 7]); ctx.lineDashOffset = -(dc.t / 60) % 16
      ctx.strokeStyle = COLORS.route; ctx.lineWidth = 2.2
      ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke()
    }
  }
  ctx.setLineDash([]); ctx.lineDashOffset = 0
}

/** Location puck: accuracy disc, pulse, white-ringed dot. */
export function locationPuck(dc: DrawCtx, at: Pt, accuracyM: number, o: { heading?: number; label?: string } = {}) {
  const { ctx, cam } = dc
  const x = cam.sx(at.x), y = cam.sy(at.y)
  const acc = accuracyM * cam.zoom
  ctx.beginPath(); ctx.arc(x, y, acc, 0, Math.PI * 2)
  ctx.fillStyle = 'rgba(47,127,216,0.13)'; ctx.fill()
  ctx.strokeStyle = 'rgba(47,127,216,0.35)'; ctx.lineWidth = 1; ctx.stroke()
  const p = (dc.t % 2000) / 2000
  ctx.beginPath(); ctx.arc(x, y, 8 + 18 * p, 0, Math.PI * 2)
  ctx.fillStyle = `rgba(47,127,216,${0.35 * (1 - p)})`; ctx.fill()
  if (o.heading !== undefined) {
    ctx.save(); ctx.translate(x, y); ctx.rotate((o.heading * Math.PI) / 180)
    const g = ctx.createLinearGradient(0, -40, 0, 0)
    g.addColorStop(0, 'rgba(47,127,216,0)'); g.addColorStop(1, 'rgba(47,127,216,0.45)')
    ctx.fillStyle = g
    ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(-16, -40); ctx.quadraticCurveTo(0, -46, 16, -40); ctx.closePath(); ctx.fill()
    ctx.restore()
  }
  ctx.save()
  ctx.shadowColor = 'rgba(0,0,0,0.3)'; ctx.shadowBlur = 5; ctx.shadowOffsetY = 1
  ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill()
  ctx.restore()
  ctx.beginPath(); ctx.arc(x, y, 6.5, 0, Math.PI * 2); ctx.fillStyle = COLORS.puck; ctx.fill()
  if (o.label) {
    ctx.font = '600 11.5px Inter Variable, Inter, system-ui, sans-serif'
    const tw = ctx.measureText(o.label).width + 14
    ctx.fillStyle = 'rgba(15,29,21,0.86)'
    ctx.beginPath(); ctx.roundRect(x + 14, y - 10, tw, 20, 10); ctx.fill()
    ctx.fillStyle = '#fff'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.fillText(o.label, x + 21, y + 0.5)
  }
}

export function measureLine(dc: DrawCtx, a: Pt, b: Pt, meters: number) {
  const { ctx, cam } = dc
  const ax = cam.sx(a.x), ay = cam.sy(a.y), bx = cam.sx(b.x), by = cam.sy(b.y)
  ctx.lineCap = 'round'
  ctx.strokeStyle = 'rgba(0,0,0,0.4)'; ctx.lineWidth = 5; ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke()
  ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.setLineDash([6, 5]); ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke(); ctx.setLineDash([])
  for (const [x, y] of [[ax, ay], [bx, by]]) { ctx.beginPath(); ctx.arc(x, y, 4.5, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill(); ctx.strokeStyle = COLORS.ink; ctx.lineWidth = 1.5; ctx.stroke() }
  const label = meters >= 100 ? `${Math.round(meters)} m` : `${meters.toFixed(1)} m`
  ctx.font = '700 12px Inter Variable, Inter, system-ui, sans-serif'
  const tw = ctx.measureText(label).width + 16
  const mx = (ax + bx) / 2, my = (ay + by) / 2
  ctx.fillStyle = 'rgba(15,29,21,0.9)'; ctx.beginPath(); ctx.roundRect(mx - tw / 2, my - 24, tw, 22, 11); ctx.fill()
  ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(label, mx, my - 12.5)
}
