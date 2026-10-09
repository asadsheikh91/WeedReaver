import { useEffect, useRef } from 'react'
import { mulberry32, white } from '../data/noise'

/**
 * Close-range frame at ~30 cm, standing in for a phone capture. Seeds map to the scan outcome sequence
 * so the photo shown next to a result matches the species the model named. Shallow depth of field is
 * faked by painting the background small and upscaling it.
 */
type Kind = 'PHALARIS' | 'AVENA' | 'CHENOPODIUM' | 'CONVOLVULUS' | 'WHEAT'
const kindFor = (seed: number): Kind => {
  if (seed === 11 || seed === 47) return 'PHALARIS'
  if (seed === 23) return 'AVENA'
  if (seed === 31) return 'CHENOPODIUM'
  if (seed === 53) return 'CONVOLVULUS'
  if (seed === 67) return 'WHEAT'
  return (['PHALARIS', 'AVENA', 'CHENOPODIUM', 'CONVOLVULUS', 'WHEAT'] as Kind[])[Math.abs(seed) % 5]
}

type C = CanvasRenderingContext2D

function blade(c: C, bx: number, by: number, tx: number, ty: number, width: number, light: string, dark: string) {
  const mx = (bx + tx) / 2 + (tx - bx) * 0.15, my = (by + ty) / 2
  c.beginPath()
  c.moveTo(bx - width, by)
  c.quadraticCurveTo(mx - width, my, tx, ty)
  c.quadraticCurveTo(mx + width, my, bx + width, by)
  c.closePath()
  const g = c.createLinearGradient(bx - width, 0, bx + width, 0)
  g.addColorStop(0, dark); g.addColorStop(0.45, light); g.addColorStop(1, dark)
  c.fillStyle = g
  c.fill()
}

function grass(c: C, w: number, h: number, rng: () => number, light: string, dark: string, o: { ligule?: boolean; hairs?: boolean; auricle?: boolean }) {
  const baseX = w * (0.42 + rng() * 0.12), baseY = h * 0.98
  c.strokeStyle = dark; c.lineWidth = w * 0.035; c.lineCap = 'round'
  c.beginPath(); c.moveTo(baseX, baseY); c.lineTo(baseX + w * 0.02, h * 0.58); c.stroke()
  const blades = [-0.55, -0.18, 0.2, 0.52]
  blades.forEach((a, i) => {
    const len = h * (0.55 + rng() * 0.25)
    const ox = baseX + w * 0.01 * i, oy = h * (0.62 + i * 0.06)
    const tx = ox + Math.sin(a) * len * 0.9, ty = oy - Math.cos(a) * len * 0.72
    const width = w * (o.hairs ? 0.05 : 0.042)
    blade(c, ox, oy, tx, ty, width, light, dark)
    c.lineWidth = w * 0.004; c.strokeStyle = 'rgba(232,245,208,0.4)'
    c.beginPath(); c.moveTo(ox, oy); c.lineTo(tx, ty); c.stroke()
    c.lineWidth = w * 0.0018; c.strokeStyle = 'rgba(32,58,24,0.2)'
    for (const k of [-2, -1, 1, 2]) { c.beginPath(); c.moveTo(ox + k * width * 0.3, oy); c.lineTo(tx, ty); c.stroke() }
    if (o.hairs) {
      c.strokeStyle = 'rgba(244,247,236,0.53)'; c.lineWidth = w * 0.0015
      for (let j = 0; j < 40; j++) {
        const t = j / 40
        const ex = ox + (tx - ox) * t - width * (1 - t) * 0.9, ey = oy + (ty - oy) * t
        c.beginPath(); c.moveTo(ex, ey); c.lineTo(ex - w * 0.012, ey - w * 0.004); c.stroke()
      }
    }
  })
  if (o.ligule) {
    const lx = baseX + w * 0.02, ly = h * 0.6
    c.beginPath()
    c.moveTo(lx - w * 0.05, ly); c.quadraticCurveTo(lx - w * 0.03, ly - h * 0.05, lx, ly - h * 0.06)
    c.quadraticCurveTo(lx + w * 0.035, ly - h * 0.05, lx + w * 0.05, ly); c.closePath()
    c.fillStyle = 'rgba(243,241,228,0.8)'; c.fill()
    c.strokeStyle = 'rgba(255,255,255,0.33)'; c.lineWidth = w * 0.003; c.stroke()
  }
  if (o.auricle) {
    c.fillStyle = '#9CC07C'
    c.beginPath(); c.ellipse(baseX - w * 0.025, h * 0.585, w * 0.025, h * 0.01, 0, 0, Math.PI * 2); c.fill()
    c.beginPath(); c.ellipse(baseX + w * 0.055, h * 0.585, w * 0.025, h * 0.01, 0, 0, Math.PI * 2); c.fill()
  }
}

function goosefoot(c: C, x: number, y: number, angle: number, size: number, young: boolean) {
  c.save()
  c.translate(x, y); c.rotate(angle + Math.PI / 2); c.translate(-x, -y)
  c.beginPath()
  c.moveTo(x, y + size * 0.9)
  c.lineTo(x - size * 0.42, y + size * 0.15); c.lineTo(x - size * 0.34, y - size * 0.1); c.lineTo(x - size * 0.2, y - size * 0.55)
  c.lineTo(x, y - size)
  c.lineTo(x + size * 0.2, y - size * 0.55); c.lineTo(x + size * 0.34, y - size * 0.1); c.lineTo(x + size * 0.42, y + size * 0.15)
  c.closePath()
  const base = young ? '#9DB58A' : '#6E9A55'
  const g = c.createLinearGradient(x - size, y, x + size, y)
  g.addColorStop(0, '#557A42'); g.addColorStop(0.5, base); g.addColorStop(1, '#557A42')
  c.fillStyle = g; c.fill()
  if (young) {
    c.filter = `blur(${size * 0.05}px)`
    c.fillStyle = 'rgba(231,236,228,0.6)'
    c.beginPath(); c.ellipse(x, y - size * 0.15, size * 0.25, size * 0.45, 0, 0, Math.PI * 2); c.fill()
    c.filter = 'none'
  }
  c.strokeStyle = 'rgba(232,240,216,0.33)'; c.lineWidth = size * 0.02
  c.beginPath(); c.moveTo(x, y + size * 0.85); c.lineTo(x, y - size * 0.9); c.stroke()
  for (let k = 1; k <= 3; k++) {
    const yy = y + size * 0.5 - k * size * 0.3
    c.beginPath(); c.moveTo(x, yy + size * 0.1); c.lineTo(x - size * 0.3, yy - size * 0.1); c.moveTo(x, yy + size * 0.1); c.lineTo(x + size * 0.3, yy - size * 0.1); c.stroke()
  }
  c.restore()
}

function arrowLeaf(c: C, x: number, y: number, side: number, size: number) {
  c.save()
  c.translate(x, y); c.rotate((side * 58 * Math.PI) / 180); c.translate(-x, -y)
  c.beginPath()
  c.moveTo(x, y); c.lineTo(x - size * 0.22, y - size * 0.05); c.lineTo(x - size * 0.34, y + size * 0.08)
  c.quadraticCurveTo(x - size * 0.2, y - size * 0.5, x, y - size)
  c.quadraticCurveTo(x + size * 0.2, y - size * 0.5, x + size * 0.34, y + size * 0.08)
  c.lineTo(x + size * 0.22, y - size * 0.05); c.closePath()
  const g = c.createLinearGradient(x - size * 0.3, y, x + size * 0.3, y)
  g.addColorStop(0, '#4F7A3C'); g.addColorStop(0.5, '#7BA861'); g.addColorStop(1, '#4F7A3C')
  c.fillStyle = g; c.fill()
  c.strokeStyle = 'rgba(234,243,218,0.33)'; c.lineWidth = size * 0.02
  c.beginPath(); c.moveTo(x, y); c.lineTo(x, y - size * 0.95); c.stroke()
  c.restore()
}

export function renderLeaf(seed: number, w: number, h: number): HTMLCanvasElement {
  const kind = kindFor(seed)
  const rng = mulberry32(seed * 7919)
  const cv = document.createElement('canvas'); cv.width = w; cv.height = h
  const c = cv.getContext('2d', { willReadFrequently: true })!

  // background: soil and out-of-focus crop, painted small then scaled up
  const bw = Math.round(w / 8), bh = Math.round(h / 8)
  const bg = document.createElement('canvas'); bg.width = bw; bg.height = bh
  const b = bg.getContext('2d')!
  const gr = b.createLinearGradient(0, 0, 0, bh)
  gr.addColorStop(0, '#4F6B3A'); gr.addColorStop(0.55, '#3B5230'); gr.addColorStop(1, '#5A4B36')
  b.fillStyle = gr; b.fillRect(0, 0, bw, bh)
  for (let i = 0; i < 26; i++) {
    const x = rng() * bw, y = rng() * bh, len = bh * (0.4 + rng() * 0.6), a = -1.2 + rng() * 0.5
    const g = 90 + Math.floor(rng() * 60)
    b.strokeStyle = `rgb(${Math.round(g * 0.62)},${g},${Math.round(g * 0.42)})`; b.lineWidth = 1.5 + rng() * 2.5
    b.beginPath(); b.moveTo(x, y); b.lineTo(x + Math.cos(a) * len * 0.3, y - Math.sin(-a) * len); b.stroke()
  }
  for (let i = 0; i < 9; i++) { b.fillStyle = 'rgba(255,244,208,0.19)'; b.beginPath(); b.arc(rng() * bw, rng() * bh * 0.6, 2 + rng() * 4, 0, Math.PI * 2); b.fill() }
  c.imageSmoothingEnabled = true; c.imageSmoothingQuality = 'high'
  c.drawImage(bg, 0, 0, w, h)

  const vg = c.createRadialGradient(w * 0.55, h * 0.45, h * 0.4, w * 0.55, h * 0.45, h * 0.78)
  vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,0.53)')
  c.fillStyle = vg; c.fillRect(0, 0, w, h)

  c.filter = `blur(${w * 0.006}px)`
  for (let i = 0; i < 7; i++) {
    const x0 = rng() * w
    blade(c, x0, h * 1.05, x0 + (rng() - 0.5) * w * 0.5, h * (0.1 + rng() * 0.3), w * 0.03, '#52763D', '#3F5E30')
  }
  c.filter = 'none'

  switch (kind) {
    case 'PHALARIS': grass(c, w, h, rng, '#8DB86A', '#6E9C50', { ligule: true }); break
    case 'AVENA': grass(c, w, h, rng, '#84AE78', '#5F8C59', { hairs: true }); break
    case 'WHEAT': grass(c, w, h, rng, '#6F9E4C', '#4F7C38', { auricle: true }); break
    case 'CHENOPODIUM': {
      const cx = w * 0.5, cy = h * 0.55
      c.strokeStyle = '#6D8C4A'; c.lineWidth = w * 0.03; c.lineCap = 'round'
      c.beginPath(); c.moveTo(cx, h); c.lineTo(cx, cy - h * 0.12); c.stroke()
      for (let i = 0; i < 7; i++) {
        const a = (i / 7) * 6.28 + rng() * 0.3, r = w * (0.2 + (i % 3) * 0.06)
        goosefoot(c, cx + Math.cos(a) * r * 0.6, cy + Math.sin(a) * r * 0.5 - (i > 4 ? h * 0.1 : 0), a, w * (0.24 - i * 0.015), i > 4)
      }
      break
    }
    case 'CONVOLVULUS': {
      c.strokeStyle = '#5E8A45'; c.lineWidth = w * 0.018; c.lineCap = 'round'
      c.beginPath(); c.moveTo(w * 0.46, h); c.lineTo(w * 0.5, 0); c.stroke()
      c.strokeStyle = '#7FA55B'; c.lineWidth = w * 0.01
      c.beginPath(); c.moveTo(w * 0.4, h)
      let yy = h, side = 1
      while (yy > h * 0.1) { c.quadraticCurveTo(w * (0.48 + side * 0.1), yy - h * 0.06, w * 0.48, yy - h * 0.12); yy -= h * 0.12; side = -side }
      c.stroke()
      for (const [fy, side2] of [[0.78, -1], [0.56, 1], [0.34, -1], [0.18, 1]] as const) arrowLeaf(c, w * (0.48 + side2 * 0.1), h * fy, side2, w * (0.2 + rng() * 0.05))
      c.fillStyle = '#F6F1F3'; c.beginPath(); c.arc(w * 0.7, h * 0.3, w * 0.07, 0, Math.PI * 2); c.fill()
      c.fillStyle = '#F1D9E2'; c.beginPath(); c.arc(w * 0.7, h * 0.3, w * 0.035, 0, Math.PI * 2); c.fill()
      c.fillStyle = '#E9D27A'; c.beginPath(); c.arc(w * 0.7, h * 0.3, w * 0.012, 0, Math.PI * 2); c.fill()
      break
    }
  }

  // sensor: warm grade, grain
  const img = c.getImageData(0, 0, w, h), d = img.data
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4
    const n = (white(x >> 1, y >> 1, seed) - 0.5) * 12
    d[i] = d[i] * 1.02 + 6 + n; d[i + 1] = d[i + 1] + 3 + n; d[i + 2] = d[i + 2] * 0.94 + n
  }
  c.putImageData(img, 0, 0)
  return cv
}

const cache = new Map<string, HTMLCanvasElement>()
export function LeafImage({ seed, w = 540, h = 720, className, style }: { seed: number; w?: number; h?: number; className?: string; style?: React.CSSProperties }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const key = `${seed}/${w}/${h}`
    let src = cache.get(key)
    let id = 0
    const paint = () => {
      src ??= renderLeaf(seed, w, h)
      cache.set(key, src)
      const el = ref.current
      if (!el) return
      el.width = w; el.height = h
      el.getContext('2d')!.drawImage(src, 0, 0)
    }
    if (src) paint(); else id = requestAnimationFrame(paint)
    return () => cancelAnimationFrame(id)
  }, [seed, w, h])
  return <canvas ref={ref} className={className} style={{ width: '100%', height: '100%', objectFit: 'cover', background: '#1A2E22', ...style }} />
}
