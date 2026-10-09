import { motion } from 'framer-motion'
import { ArrowLeftRight, Check, Printer, Volume2, Wheat, X, AlertTriangle, Clock } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { fmt } from '../data/format'
import { useGrid, useVerification } from '../data/queries'
import { surveysOf, useStore } from '../data/store'
import { SURVEY_ROLE, WEED_CLASS } from '../data/types'
import { MapView } from '../map/MapView'
import { dimOutside, fieldOutline, heatLayer, type DrawCtx } from '../map/overlays'
import { BeforeAfterBar } from '../ui/charts'
import { Button, Card, Empty, InfoButton, Meter, Modal, Notice, Pill, Segmented, SectionHead, Stat, ZoneBadge, cx } from '../ui/kit'
import { BrandMark } from '../ui/brand'
import { FieldSwitcher, PageHead, useFieldData } from './common'
import { now } from '../data/clock'
import './verification.css'

export default function Verification() {
  const { fieldId } = useParams()
  const nav = useNavigate()
  const { field, surveyed, gridSize } = useFieldData(fieldId)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const threshold = useStore((s) => s.pushedThreshold)
  const published = useStore((s) => s.zones[field.id])
  const [which, setWhich] = useState<'PLUS_14D' | 'PLUS_28D'>('PLUS_14D')
  const [split, setSplit] = useState(0.5)
  const [sheet, setSheet] = useState(false)

  const list = surveysOf({ seasons, surveys }, field.id)
  const pre = list.find((s) => s.role === 'PRE')
  const follow = list.find((s) => s.role === which)
  const verQuery = useVerification(field.id, which, surveyed)
  const ver = verQuery.data?.fieldId === field.id && verQuery.data.role === which ? verQuery.data : undefined
  const ready = surveyed && follow?.status === 'READY' && !!ver?.ready
  // the swipe compares the two flights' grids at the published threshold
  const post = useGrid(field.id, gridSize, threshold, which, ready)
  const before = useGrid(field.id, gridSize, threshold, 'PRE', ready)

  const rows = (ver?.zones ?? []).flatMap((e) => {
    const z = published?.find((x) => x.letter === e.letter)
    return z ? [{ z, pct: e.efficacyPct, beforePct: e.beforePct, afterPct: e.afterPct, inspect: e.inspect }] : []
  })
  const weak = rows.filter((r) => r.inspect)
  const worst = [...rows].sort((a, b) => a.pct - b.pct)[0]
  const scale = Math.max(1, ...rows.map((r) => r.beforePct))
  const saved = before ? 1 - before.treatedFraction : ver?.chemicalSavedFraction ?? 0
  const delta = ver?.fieldDeltaPct ?? (before && post ? Math.round((1 - post.flagged / Math.max(1, before.flagged)) * 100) : 0)

  const draw = (dc: DrawCtx) => {
    dimOutside(dc, field.boundary, 0.28)
    if (before && post) {
      const x = dc.w * split
      const { ctx } = dc
      ctx.save(); ctx.beginPath(); ctx.rect(0, 0, x, dc.h); ctx.clip(); heatLayer(dc, before, field.boundary, 1, false); ctx.restore()
      ctx.save(); ctx.beginPath(); ctx.rect(x, 0, dc.w - x, dc.h); ctx.clip(); heatLayer(dc, post, field.boundary, 1, false); ctx.restore()
    }
    fieldOutline(dc, field.boundary, { width: 1.8 })
    rows.forEach(({ z, pct }) => {
      const x = dc.cam.sx(z.cx), y = dc.cam.sy(z.cy)
      const { ctx } = dc
      ctx.font = '700 11.5px Inter Variable, Inter, sans-serif'
      const label = `${z.letter} · ${pct}%`
      const w = ctx.measureText(label).width + 16
      ctx.fillStyle = pct >= 70 ? 'rgba(30,74,51,.92)' : 'rgba(147,63,29,.94)'
      ctx.beginPath(); ctx.roundRect(x - w / 2, y - 12, w, 24, 12); ctx.fill()
      ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(label, x, y + 0.5)
    })
  }

  return (
    <>
      <PageHead title="Verification" sub="Did the treatment work? The follow-up flight is compared with the pre-treatment survey, zone by zone, never as a field average. A mean hides the zone where control failed, which is the zone that matters.">
        <FieldSwitcher value={field.id} onChange={(id) => nav(`/app/verification/${id}`)} />
        <Segmented value={which} onChange={setWhich} options={[{ value: 'PLUS_14D', label: '+14 d' }, { value: 'PLUS_28D', label: '+28 d' }]} />
      </PageHead>

      {!ready || !before || !post ? (
        <Card pad>
          <Empty icon={Clock} title={!surveyed ? 'No pre-treatment survey' : ready ? 'Loading the two flights…' : follow ? `${SURVEY_ROLE[which].label} is ${follow.status === 'PROCESSING' ? `processing (${Math.round(follow.progress * 100)}%)` : follow.status.toLowerCase()}` : 'No follow-up planned'}>
            {!surveyed ? 'Verification compares two flights, so it needs a processed pre-treatment survey first.' : ready ? '' : follow ? (follow.status === 'PROCESSING' || follow.status === 'QUEUED' ? 'The comparison appears here the moment the orthomosaic is ready.' : follow.status === 'READY' ? ver?.reason ?? '' : `The ${SURVEY_ROLE[which].label.toLowerCase()} flight is planned for ${fmt.weekdayShort(follow.flownAt)} (${fmt.inDays(follow.flownAt)}).`) : 'Schedule a follow-up survey on the Flights page.'}
            {follow?.status === 'PROCESSING' && <div style={{ width: 280, margin: '16px auto 0' }}><Meter value={follow.progress} tone="slate" lg /></div>}
          </Empty>
        </Card>
      ) : (
        <>
          <div className="ver__grid">
            <Card flush>
              <div className="ver__map">
                <MapView field={field} surveyed fitPad={26} coords={false} draw={draw}>
                  <div className="ver__chip ver__chip--l">Before · {fmt.dayMonth(pre!.flownAt)}</div>
                  <div className="ver__chip ver__chip--r">After · {fmt.dayMonth(follow!.flownAt)}</div>
                  <Handle split={split} onChange={setSplit} />
                </MapView>
              </div>
              <div className="ver__foot">
                <span className="row gap-8 body-s"><ArrowLeftRight size={15} /> Drag the handle to compare the two flights</span>
                <span className="row gap-16 caption"><span className="row gap-6"><i className="sw" style={{ background: 'var(--heat-mid)' }} />10–30%</span><span className="row gap-6"><i className="sw" style={{ background: 'var(--heat-high)' }} />over 30%</span></span>
              </div>
            </Card>
            <div className="col gap-16">
              <Card pad>
                <div className="ver__stats">
                  <Stat value={before!.flagged} format={fmt.int} label="Cells above threshold before" />
                  <Stat value={post!.flagged} format={fmt.int} label="After" tone="forest" />
                  <Stat value={delta} format={(n) => `${n >= 0 ? '−' : '+'}${Math.abs(Math.round(n))}%`} label="Field-wide" tone={delta >= 0 ? 'forest' : 'clay'} />
                  <Stat value={weak.length} label="Zones to inspect" tone={weak.length ? 'clay' : 'forest'} />
                </div>
              </Card>
              <Card pad>
                <div className="row between"><div className="overline">Chemical saved</div><InfoButton title="What this does and does not claim" body={<><p>Spot spraying only the prescription cells uses less herbicide than a blanket spray at equal control. That saving is measured against a blanket-spray baseline.</p><p>It is not a yield gain. Spatial targeting is close to free in yield terms, and the yield claim is measured separately, against a different baseline.</p></>} /></div>
                <div className="row gap-12" style={{ alignItems: 'baseline', marginTop: 6 }}>
                  <span className="num-xl" style={{ color: 'var(--forest)' }}><AnimNum v={saved * 100} />%</span>
                  <span className="body-s">less than a blanket spray</span>
                </div>
                <div style={{ marginTop: 12 }}><Meter value={saved} tone="forest" lg /></div>
                <p className="caption" style={{ marginTop: 10 }}>At equal control, on this prescription, at {gridSize} m. Published threshold {threshold}%.</p>
              </Card>
              <Button icon={Printer} onClick={() => setSheet(true)}>Farmer handover sheet</Button>
            </div>
          </div>

          {weak.length > 0 && worst && (
            <div style={{ marginTop: 24 }}>
              <Notice tone="clay" icon={AlertTriangle} title={`${worst.z.label} barely responded: ${worst.pct}% control`}
                action={<button className="link-btn" onClick={() => nav(`/app/rotation/${field.id}`)}>Open rotation history →</button>}>
                Survivors at label rate look like a resistant patch, not a missed spray. Check which mode of action has gone onto this zone before recommending another application.
              </Notice>
            </div>
          )}

          <SectionHead title="Control by zone" sub={`${SURVEY_ROLE[which].label} vs pre-treatment cover`} />
          <Card flush>
            {rows.map(({ z, pct, beforePct, afterPct }, i) => (
              <motion.div key={z.id} className="zrow" initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: i * 0.06 }}>
                <ZoneBadge z={z} size={40} />
                <div className="zrow__name"><div className="title-s">{z.label}</div><div className="caption">{WEED_CLASS[z.dominantClass].short} · {fmt.sqm(z.areaSqm)}</div></div>
                <div className="grow"><BeforeAfterBar before={beforePct} after={afterPct} scale={scale} /><div className="caption" style={{ marginTop: 4 }}>{Math.round(beforePct)}% → {Math.round(afterPct)}% cover</div></div>
                <div className="zrow__pct" style={{ color: pct >= 70 ? 'var(--forest)' : 'var(--clay-ink)' }}>{pct}%</div>
                <div style={{ width: 92, textAlign: 'right' }}>{pct >= 70 ? <Pill tone="forest" icon={Check}>Controlled</Pill> : <Pill tone="clay" icon={X}>Inspect</Pill>}</div>
              </motion.div>
            ))}
          </Card>
          <p className="caption" style={{ marginTop: 12 }}><span className="row gap-16"><span className="row gap-6"><i className="sw" style={{ background: 'var(--clay)', opacity: 0.85 }} />Before</span><span className="row gap-6"><i className="sw" style={{ background: 'var(--moss)' }} />After</span></span></p>

          <HandoverSheet open={sheet} onClose={() => setSheet(false)} fieldId={field.id} />
        </>
      )}
    </>
  )
}

function AnimNum({ v }: { v: number }) {
  const [x, setX] = useState(0)
  useEffect(() => {
    const t0 = performance.now(); let raf = 0
    const tick = (t: number) => { const k = Math.min(1, (t - t0) / 900); setX(v * (1 - Math.pow(1 - k, 3))); if (k < 1) raf = requestAnimationFrame(tick) }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [v])
  return <>{Math.round(x)}</>
}

/** The swipe handle: a vertical line with a grip, dragged across the map. */
function Handle({ split, onChange }: { split: number; onChange: (v: number) => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const drag = useRef(false)
  const move = (clientX: number) => {
    const r = ref.current?.parentElement?.getBoundingClientRect()
    if (!r) return
    onChange(Math.min(0.97, Math.max(0.03, (clientX - r.left) / r.width)))
  }
  return (
    <div ref={ref} className="ver__handle" style={{ left: `${split * 100}%` }}
      onPointerDown={(e) => { drag.current = true; e.currentTarget.setPointerCapture(e.pointerId) }}
      onPointerMove={(e) => drag.current && move(e.clientX)}
      onPointerUp={() => (drag.current = false)}
      onKeyDown={(e) => { if (e.key === 'ArrowLeft') onChange(Math.max(0.03, split - 0.03)); if (e.key === 'ArrowRight') onChange(Math.min(0.97, split + 0.03)) }}
      role="slider" tabIndex={0} aria-label="Compare before and after" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(split * 100)}>
      <span className="ver__grip"><ArrowLeftRight size={17} /></span>
    </div>
  )
}

/* ------------------------------------------------------------------ handover */

function HandoverSheet({ open, onClose, fieldId }: { open: boolean; onClose: () => void; fieldId: string }) {
  const { field, zones, grid } = useFieldData(fieldId)
  const toast = useStore((s) => s.toast)
  const me = useStore((s) => s.me)
  const org = useStore((s) => s.station?.orgName ?? '')
  const [playing, setPlaying] = useState(false)
  useEffect(() => { if (playing) { const t = setTimeout(() => setPlaying(false), 5000); return () => clearTimeout(t) } }, [playing])
  const draw = (dc: DrawCtx) => {
    dimOutside(dc, field.boundary, 0.25)
    if (grid) heatLayer(dc, grid, field.boundary, 1, false)
    fieldOutline(dc, field.boundary, { width: 2.4 })
    zones.forEach((z) => {
      if (z.efficacyPct == null) return
      const ok = z.efficacyPct >= 70
      const { ctx } = dc
      const x = dc.cam.sx(z.cx), y = dc.cam.sy(z.cy)
      ctx.beginPath(); ctx.arc(x, y, 17, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill()
      ctx.beginPath(); ctx.arc(x, y, 13.5, 0, Math.PI * 2); ctx.fillStyle = ok ? '#2D6547' : '#B9552F'; ctx.fill()
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 3; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.beginPath()
      if (ok) { ctx.moveTo(x - 5.5, y); ctx.lineTo(x - 1.5, y + 4.5); ctx.lineTo(x + 6, y - 4.5) } else { ctx.moveTo(x - 5, y - 5); ctx.lineTo(x + 5, y + 5); ctx.moveTo(x + 5, y - 5); ctx.lineTo(x - 5, y + 5) }
      ctx.stroke()
    })
  }
  return (
    <Modal open={open} onClose={onClose} wide title="Farmer handover" sub="The farmer is not a software user. What leaves the station is a printed colour map and a recorded voice message. No load-bearing text."
      footer={<><Button variant="ghost" onClick={onClose}>Close</Button><Button variant="primary" icon={Printer} onClick={() => window.print()}>Print the sheet</Button></>}>
      <div className="sheet" id="print-sheet">
        <div className="sheet__head">
          <BrandMark size={40} />
          <div className="grow"><div className="serif" style={{ fontSize: 26, letterSpacing: '-0.02em' }}>{field.name}</div><div className="caption">{field.village} · {fmt.date(now())}</div></div>
          <Wheat size={40} color="var(--moss)" />
        </div>
        <div className="sheet__map"><MapView field={field} surveyed interactive={false} controls={false} scaleBar={false} fitPad={22} draw={draw} /></div>
        <div className="sheet__zones">
          {zones.filter((z) => z.efficacyPct != null).map((z) => {
            const pct = z.efficacyPct!; const ok = pct >= 70
            return (
              <div key={z.id} className={cx('sheet__zone', ok ? 'is-ok' : 'is-bad')}>
                <span className="sheet__mark">{ok ? <Check size={26} /> : <X size={26} />}</span>
                <span className="sheet__letter">{z.letter}</span>
                <span className="sheet__pct">{pct}%</span>
              </div>
            )
          })}
        </div>
        <div className="sheet__legend"><span><i style={{ background: 'var(--forest-2)' }} /> Weeds are down</span><span><i style={{ background: 'var(--clay)' }} /> Not working: ask the agronomist</span></div>
        <div className="sheet__foot">Prepared by {me?.name} · {org}</div>
      </div>
      <div className="voice">
        <Button variant={playing ? 'primary' : 'tonal'} icon={Volume2} onClick={() => { setPlaying(!playing); if (!playing) toast('Playing the recorded Punjabi message') }}>{playing ? 'Playing…' : 'Play voice message (Punjabi)'}</Button>
        <div className="voice__wave">{Array.from({ length: 36 }).map((_, i) => <i key={i} className={playing ? 'is-live' : ''} style={{ height: `${20 + Math.abs(Math.sin(i * 0.9) * Math.cos(i * 0.37)) * 70}%`, animationDelay: `${i * 40}ms` }} />)}</div>
        <span className="caption">0:48 · recorded by a person, not text-to-speech</span>
      </div>
    </Modal>
  )
}
