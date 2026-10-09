import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { bounds } from '../data/geo'
import { useGrid, useVerification, useZonePreview } from '../data/queries'
import { hasSurvey, surveysOf, useStore } from '../data/store'
import type { FieldParcel } from '../data/types'
import { cachedImagery, loadImagery, onImageryReady, specFor } from '../map/aerial'
import { SelectInput } from '../ui/kit'

export function PageHead({ title, sub, children }: { title: ReactNode; sub?: ReactNode; children?: ReactNode }) {
  return (
    <div className="page__head">
      <div>
        <h1>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      {children && <div className="page__actions">{children}</div>}
    </div>
  )
}

/**
 * Everything a page needs to know about one field. Zones are the published ones (with the state the phones
 * reported) at the published threshold, or a server preview at the slider's value while it differs; the
 * grids come from the API at the current grid size and threshold.
 */
export function useFieldData(fieldId?: string) {
  const fields = useStore((s) => s.fields)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const threshold = useStore((s) => s.threshold)
  const pushed = useStore((s) => s.pushedThreshold)
  const gridSize = useStore((s) => s.gridSize)
  const published = useStore((s) => s.zones)
  const field = fields.find((f) => f.id === fieldId) ?? fields[0]
  const surveyed = hasSurvey({ seasons, surveys }, field.id)
  const followReady = surveysOf({ seasons, surveys }, field.id).some((x) => x.role === 'PLUS_14D' && x.status === 'READY')
  const draft = threshold !== pushed
  const preview = useZonePreview(field.id, threshold, surveyed && draft)
  const verification = useVerification(field.id, 'PLUS_14D', surveyed && followReady).data
  const zones = useMemo(() => {
    const base = published[field.id] ?? []
    const efficacy = new Map((verification?.ready ? verification.zones : []).map((z) => [z.letter, z.efficacyPct]))
    const byLetter = new Map(base.map((z) => [z.letter, z]))
    // zone letters are stable across re-zoning, so the phones' observations carry over to a preview
    const list = draft && preview ? preview.map((z) => { const p = byLetter.get(z.letter); return p ? { ...z, state: p.state, treatedAt: p.treatedAt } : z }) : base
    return surveyed ? list.map((z) => ({ ...z, efficacyPct: efficacy.get(z.letter) ?? z.efficacyPct })) : []
  }, [published, field.id, draft, preview, verification, surveyed])
  const grid = useGrid(field.id, gridSize, threshold, 'PRE', surveyed)
  const followGrid = useGrid(field.id, gridSize, threshold, 'PLUS_14D', surveyed && followReady)
  return { field, fields, surveyed, zones, grid, followGrid, threshold, gridSize, verification }
}

/** Zone lists for every field at once, for portfolio views: what is published to the phones. */
export function usePortfolio() {
  const fields = useStore((s) => s.fields)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const zones = useStore((s) => s.zones)
  return useMemo(() => fields.map((field) => {
    const surveyed = hasSurvey({ seasons, surveys }, field.id)
    return { field, surveyed, zones: surveyed ? zones[field.id] ?? [] : [] }
  }), [fields, seasons, surveys, zones])
}

export function FieldSwitcher({ value, onChange, only }: { value: string; onChange: (id: string) => void; only?: (f: FieldParcel) => boolean }) {
  const fields = useStore((s) => s.fields)
  const list = only ? fields.filter(only) : fields
  return (
    <SelectInput sm aria-label="Field" value={value} onChange={(e) => onChange(e.target.value)} style={{ width: 200, fontWeight: 600 }}>
      {list.map((f) => <option key={f.id} value={f.id}>{f.name} · {f.id}</option>)}
    </SelectInput>
  )
}

/** Route-param-aware field switcher: /app/<base>/:fieldId */
export function useFieldRoute(base: string, param?: string) {
  const nav = useNavigate()
  const fields = useStore((s) => s.fields)
  const id = fields.find((f) => f.id === param)?.id ?? fields[0]?.id ?? ''
  return [id, (next: string) => nav(`/app/${base}/${next}`)] as const
}

/** Lightweight thumbnail: crops the cached mosaic to the parcel, without a full map view. */
export function FieldThumb({ field, surveyed, width = 88, height = 64, radius = 12 }: { field: FieldParcel; surveyed: boolean; width?: number; height?: number; radius?: number }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const spec = specFor(field, surveyed)
    const draw = () => {
      const img = cachedImagery(spec.key)
      const el = ref.current
      if (!img || !el) return
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      el.width = width * dpr; el.height = height * dpr
      const c = el.getContext('2d')!
      c.scale(dpr, dpr)
      const b = bounds(field.boundary)
      const pad = 10
      const bw = b[2] - b[0] + pad * 2, bh = b[3] - b[1] + pad * 2
      const k = Math.min(width / bw, height / bh)
      const ox = (width - bw * k) / 2 - (b[0] - pad) * k, oy = (height - bh * k) / 2 - (b[1] - pad) * k
      c.fillStyle = '#B3AE95'; c.fillRect(0, 0, width, height)
      c.drawImage(img.bitmap, ox + spec.minX * k, oy + spec.minY * k, (spec.maxX - spec.minX) * k, (spec.maxY - spec.minY) * k)
      c.beginPath()
      field.boundary.forEach((p, i) => (i ? c.lineTo(ox + p.x * k, oy + p.y * k) : c.moveTo(ox + p.x * k, oy + p.y * k)))
      c.closePath()
      c.lineWidth = 3.2; c.strokeStyle = 'rgba(0,0,0,.28)'; c.stroke()
      c.lineWidth = 1.6; c.strokeStyle = '#fff'; c.stroke()
    }
    draw()
    void loadImagery(spec).then(draw)
    return onImageryReady(draw)
  }, [field, surveyed, width, height])
  return <canvas ref={ref} style={{ width, height, borderRadius: radius, background: '#B3AE95', flex: 'none', border: '1px solid rgba(0,0,0,.06)' }} />
}
