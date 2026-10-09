import type { Pt } from '../data/types'

export interface Insets { top: number; right: number; bottom: number; left: number }
export const NO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 }

/**
 * Camera over a local metric frame. Zoom is in CSS pixels per metre. The visible area can be inset
 * (a side panel covering part of the map) so "fit" and "centre" mean the part the operator can see.
 */
export class Camera {
  cx = 0
  cy = 0
  zoom = 0
  width = 0
  height = 0
  insets: Insets = NO_INSETS
  minZoom = 0.5
  maxZoom = 14
  world: [number, number, number, number] = [-1e6, -1e6, 1e6, 1e6]
  userMoved = false

  get ready() { return this.zoom > 0 && this.width > 0 }
  private get fx() { return this.insets.left + (this.width - this.insets.left - this.insets.right) / 2 }
  private get fy() { return this.insets.top + (this.height - this.insets.top - this.insets.bottom) / 2 }

  sx(x: number) { return (x - this.cx) * this.zoom + this.fx }
  sy(y: number) { return (y - this.cy) * this.zoom + this.fy }
  project(p: Pt): [number, number] { return [this.sx(p.x), this.sy(p.y)] }
  wx(sx: number) { return (sx - this.fx) / this.zoom + this.cx }
  wy(sy: number) { return (sy - this.fy) / this.zoom + this.cy }
  unproject(sx: number, sy: number): Pt { return { x: this.wx(sx), y: this.wy(sy) } }

  fitZoom(b: [number, number, number, number], pad: number) {
    const bw = Math.max(1, b[2] - b[0]), bh = Math.max(1, b[3] - b[1])
    const vw = this.width - this.insets.left - this.insets.right - pad * 2
    const vh = this.height - this.insets.top - this.insets.bottom - pad * 2
    return Math.min(this.maxZoom, Math.max(this.minZoom, Math.min(vw / bw, vh / bh)))
  }

  snapTo(b: [number, number, number, number], pad: number) {
    this.zoom = this.fitZoom(b, pad)
    this.cx = (b[0] + b[2]) / 2
    this.cy = (b[1] + b[3]) / 2
    this.clamp()
  }

  clamp() {
    this.cx = Math.min(this.world[2], Math.max(this.world[0], this.cx))
    this.cy = Math.min(this.world[3], Math.max(this.world[1], this.cy))
  }

  /** Zoom by a factor keeping the world point under (sx, sy) fixed. */
  zoomAt(sx: number, sy: number, factor: number) {
    const ax = this.wx(sx), ay = this.wy(sy)
    this.zoom = Math.min(this.maxZoom, Math.max(this.minZoom, this.zoom * factor))
    this.cx += ax - this.wx(sx)
    this.cy += ay - this.wy(sy)
    this.clamp()
    this.userMoved = true
  }

  panBy(dx: number, dy: number) {
    this.cx -= dx / this.zoom
    this.cy -= dy / this.zoom
    this.clamp()
    this.userMoved = true
  }
}

export const easeEmphasized = (t: number) => {
  // cubic-bezier(0.2, 0, 0, 1) solved numerically
  let lo = 0, hi = 1, x = t
  for (let i = 0; i < 16; i++) {
    x = (lo + hi) / 2
    const bx = 3 * (1 - x) * (1 - x) * x * 0.2 + x * x * x
    if (bx < t) lo = x; else hi = x
  }
  return 3 * (1 - x) * (1 - x) * x * 0 + 3 * (1 - x) * x * x * 1 + x * x * x
}
