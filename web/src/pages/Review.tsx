import { AnimatePresence, motion } from 'framer-motion'
import { Check, CheckCheck, Frame, Inbox, MapPin, RotateCcw, Send, Smartphone, Timer, Undo2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { ReviewCellsDto } from '../api/dto'
import { fmt } from '../data/format'
import { fromLatLon } from '../data/geo'
import { useGrid, useQuery, useReviewCells } from '../data/queries'
import { useStore } from '../data/store'
import { WEED_CLASS, type FieldParcel, type LeafScan } from '../data/types'
import { LeafImage } from '../map/leaf'
import { MapView } from '../map/MapView'
import { dimOutside, fieldOutline, heatLayer, locationPuck, type DrawCtx } from '../map/overlays'
import { Button, Card, Empty, Field, Pill, Ring, Tabs, cx } from '../ui/kit'
import { PageHead } from './common'
import './review.css'

export default function Review() {
  const scans = useStore((s) => s.scans)
  const fields = useStore((s) => s.fields)
  const [params] = useSearchParams()
  const [tab, setTab] = useState<'scans' | 'cells'>(params.get('tab') === 'cells' ? 'cells' : 'scans')

  const waiting = scans.filter((s) => s.abstained && !s.resolved)
  const review = useReviewCells().data
  const cells = useMemo(() => (review ?? []).filter((x) => x.count > 0).flatMap((x) => {
    const f = fields.find((ff) => ff.id === x.fieldId)
    return f ? [{ f, r: x }] : []
  }), [review, fields])
  const cellTotal = cells.reduce((n, x) => n + x.r.count, 0)

  return (
    <>
      <PageHead title="Review queue" sub="Where the models declined to answer. An abstained scan or grid cell is not a gap in the data: it is an instruction to send a person to look.">
        <Pill tone={waiting.length ? 'wheat' : 'forest'} lg dot>{waiting.length ? `${waiting.length} scans waiting` : 'Scan queue clear'}</Pill>
      </PageHead>
      <Tabs value={tab} onChange={setTab} options={[
        { value: 'scans', label: <span className="row gap-8">Leaf scans <span className="count-badge count-badge--wheat">{waiting.length}</span></span> },
        { value: 'cells', label: <span className="row gap-8">Grid cells <span className="count-badge count-badge--wheat">{cellTotal}</span></span> },
      ]} />
      <div style={{ height: 24 }} />
      {tab === 'scans' ? <ScanReview /> : <CellReview cells={cells} />}
    </>
  )
}

/* ------------------------------------------------------------------ scans */

function ScanReview() {
  const scans = useStore((s) => s.scans)
  const fields = useStore((s) => s.fields)
  const resolve = useStore((s) => s.resolveScan)
  const reopen = useStore((s) => s.reopenScan)
  const toast = useStore((s) => s.toast)
  const species = useStore((s) => s.species)
  const speciesByLatin = (latin: string) => species.find((x) => x.latin === latin)
  const waiting = scans.filter((s) => s.abstained && !s.resolved)
  const done = scans.filter((s) => s.abstained && s.resolved)
  const confident = scans.filter((s) => !s.abstained)
  const [selId, setSelId] = useState<string | null>(waiting[0]?.id ?? null)
  const [pick, setPick] = useState<string | null>(null)
  const [other, setOther] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const sel = scans.find((s) => s.id === selId) ?? waiting[0] ?? done[0]

  useEffect(() => { setPick(sel?.runnerUp ? sel.suggestion ?? sel.speciesLatin : null); setOther(''); setNote('') }, [sel?.id, sel?.speciesLatin, sel?.suggestion])
  useEffect(() => { if (selId && !scans.some((s) => s.id === selId)) setSelId(null) }, [scans, selId])

  if (!sel) return <Empty icon={Inbox} title="Nothing to review">Scans the model declines will land here.</Empty>
  const field = fields.find((f) => f.id === sel.fieldId)
  const candidates: [string, number][] = [[sel.speciesLatin, sel.confidence], ...sel.runnerUp]
  if (sel.suggestion && !candidates.some(([n]) => n === sel.suggestion)) candidates.push([sel.suggestion, 0])
  const label = pick === '__other' ? other : pick

  return (
    <div className="rv">
      <div className="rv__list">
        <div className="overline" style={{ padding: '0 4px 8px' }}>Waiting · {waiting.length}</div>
        <div className="col gap-8">
          <AnimatePresence initial={false}>
            {waiting.map((s) => <ScanItem key={s.id} s={s} active={s.id === sel.id} onClick={() => setSelId(s.id)} />)}
          </AnimatePresence>
          {waiting.length === 0 && (
            <Card pad style={{ background: 'var(--sage-tint)', borderColor: 'var(--sage-line)' }}>
              <div className="row gap-12"><CheckCheck color="var(--forest)" /><div><div className="title-s" style={{ color: 'var(--forest)' }}>Queue is clear</div><div className="body-s">Every abstained scan has a label.</div></div></div>
            </Card>
          )}
        </div>
        {done.length > 0 && <>
          <div className="overline" style={{ padding: '22px 4px 8px' }}>Labelled · {done.length}</div>
          <div className="col gap-8">{done.map((s) => <ScanItem key={s.id} s={s} active={s.id === sel.id} onClick={() => setSelId(s.id)} />)}</div>
        </>}
        <div className="overline" style={{ padding: '22px 4px 8px' }}>Confident · {confident.length}</div>
        <div className="col gap-8">{confident.map((s) => <ScanItem key={s.id} s={s} active={s.id === sel.id} onClick={() => setSelId(s.id)} />)}</div>
      </div>

      <motion.div key={sel.id} className="rv__detail" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }}>
        <Card flush>
          <div className="rv__top">
            <div className="rv__photo">
              <ScanPhoto scan={sel} w={540} h={720} />
              <div className={cx('rv__box', sel.abstained && 'rv__box--warn')}><span>{sel.abstained ? `Uncertain · ${fmt.pct(sel.confidence)}` : `${sel.speciesLatin} · ${fmt.pct(sel.confidence)}`}</span></div>
              {sel.frameId && <div className="rv__frame"><Frame size={13} /> {sel.frameId}</div>}
            </div>
            <div className="rv__info">
              <div className="row gap-8 wrap">
                <Pill tone={sel.abstained ? (sel.resolved ? 'forest' : 'wheat') : 'moss'} dot>{sel.abstained ? (sel.resolved ? 'Labelled' : 'Needs a label') : 'Confident'}</Pill>
                <span className="mono">{sel.id}</span>
                {sel.deviceName && <Pill tone="neutral" icon={Smartphone}>{sel.deviceName}</Pill>}
              </div>
              <h2 className="display-m" style={{ marginTop: 14 }}>{sel.resolved ? <span className="italic">{sel.annotation}</span> : sel.abstained ? 'Not confident enough' : <span className="italic">{sel.speciesLatin}</span>}</h2>
              <p className="body-s" style={{ marginTop: 4 }}>{sel.abstained && !sel.resolved
                ? 'A wrong answer here means a wrong spray, so the model declined. Choose what you can see in the photo.'
                : sel.resolved ? `Labelled by ${sel.resolvedBy} · ${fmt.relative(sel.resolvedAt!)}.` : `${speciesByLatin(sel.speciesLatin)?.common} · ${sel.speciesLocal} · ${WEED_CLASS[sel.weedClass].label}`}</p>

              <div className="rv__conf">
                <Ring value={sel.confidence} size={78} stroke={7} color={sel.abstained ? 'var(--wheat)' : 'var(--moss)'}>
                  <div className="col" style={{ alignItems: 'center' }}><span className="num-m">{Math.round(sel.confidence * 100)}</span><span className="caption" style={{ fontSize: 10 }}>% sure</span></div>
                </Ring>
                <div className="grow">
                  <div className="overline">Location</div>
                  <div className="mono" style={{ color: 'var(--ink)', marginTop: 2 }}>{sel.lat != null && sel.lon != null ? fmt.coord(sel.lat, sel.lon) : 'No GNSS fix'}</div>
                  <div className="caption">{sel.fieldName}{sel.zoneLabel ? ` · ${sel.zoneLabel}` : ''}{sel.gnssAccuracyM ? ` · GNSS ±${sel.gnssAccuracyM.toFixed(1)} m` : ''}</div>
                </div>
              </div>
              {field && sel.lat != null && sel.lon != null && <div className="rv__mini"><ScanMap field={field} scan={sel} /></div>}
            </div>
          </div>
        </Card>

        {sel.abstained && !sel.resolved && (
          <Card pad style={{ marginTop: 16 }}>
            <div className="row between"><h3 className="title-m">What is it?</h3><span className="caption">Top model candidates</span></div>
            <div className="col gap-8 mt-12">
              {candidates.map(([name, p], i) => {
                const sp = speciesByLatin(name)
                return (
                  <button key={name} className={cx('cand', pick === name && 'is-on')} onClick={() => setPick(name)} aria-pressed={pick === name}>
                    <span className="cand__radio" />
                    <span className="grow" style={{ textAlign: 'left' }}>
                      <span className="title-s italic">{name}</span>
                      <span className="caption" style={{ display: 'block' }}>{sp ? `${sp.common} · ${sp.local}` : 'Not in the label set'}{name === sel.suggestion ? ' · suggested by the operator' : ''}</span>
                    </span>
                    <span className="cand__bar"><i style={{ width: `${p * 100}%`, background: i === 0 ? 'var(--wheat)' : 'var(--line-strong)' }} /></span>
                    <span className="title-s" style={{ width: 40, textAlign: 'right' }}>{fmt.pct(p)}</span>
                  </button>
                )
              })}
              <button className={cx('cand', pick === '__other' && 'is-on')} onClick={() => setPick('__other')} aria-pressed={pick === '__other'}>
                <span className="cand__radio" /><span className="grow title-s" style={{ textAlign: 'left' }}>Something else</span>
                {pick === '__other' && (
                  <select className="select select--sm" style={{ width: 230 }} value={other} onClick={(e) => e.stopPropagation()} onChange={(e) => setOther(e.target.value)}>
                    <option value="">Choose a species…</option>
                    {species.map((s) => <option key={s.latin} value={s.latin}>{s.latin}</option>)}
                    <option value="Unidentified weed">Unidentified weed</option>
                  </select>
                )}
              </button>
            </div>
            <div className="mt-16"><Field label="Note for the record" optional><textarea className="input" rows={2} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Ligule visible, awns twisted, plant near the bund…" /></Field></div>
            <div className="row gap-12 mt-16 end">
              <Button icon={RotateCcw} onClick={() => toast(`Rescan noted. Ask ${sel.deviceName ?? 'the operator'} to scan ${sel.zoneLabel ?? 'this spot'} again.`)}>Ask for a rescan</Button>
              <Button variant="primary" icon={Check} disabled={!label} loading={busy} onClick={() => {
                setBusy(true)
                void resolve(sel.id, label!, note).then(() => { setBusy(false); const next = waiting.find((w) => w.id !== sel.id); setSelId(next?.id ?? sel.id) })
              }}>Confirm label</Button>
            </div>
          </Card>
        )}

        {sel.resolved && (
          <Card pad style={{ marginTop: 16 }}>
            <div className="row gap-16">
              <div className="icon-tile"><Check /></div>
              <div className="grow"><div className="title-s">Added to the reviewed set for the next model release</div><div className="body-s">Resolved cases sharpen the risk-coverage curve between releases. {sel.note && <>Note: “{sel.note}”</>}</div></div>
              <Button variant="ghost" icon={Undo2} onClick={() => void reopen(sel.id)}>Reopen</Button>
            </div>
          </Card>
        )}

        <Card pad style={{ marginTop: 16 }}>
          <div className="rv__meta">
            <div><div className="overline">Captured</div><div className="title-s">{fmt.relative(sel.at)}</div></div>
            <div><div className="overline">Model</div><div className="title-s mono" style={{ color: 'var(--ink)' }}>{sel.modelVersion.split(' ')[0].replace('wr-leaf-', '')}</div></div>
            <div><div className="overline">Inference</div><div className="title-s row gap-6"><Timer size={14} />{sel.inferenceMs ? `${sel.inferenceMs} ms` : '—'}</div></div>
            <div><div className="overline">Upload</div><div className="title-s">{sel.synced ? 'Synced' : 'On phone'}</div></div>
          </div>
        </Card>
      </motion.div>
    </div>
  )
}

function ScanItem({ s, active, onClick }: { s: LeafScan; active: boolean; onClick: () => void }) {
  const title = s.abstained ? (s.resolved ? s.annotation : 'Needs a label') : s.speciesLatin
  return (
    <motion.button layout initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -16, height: 0 }} onClick={onClick} className={cx('scan-item', active && 'is-active')}>
      <div className="scan-item__img"><ScanPhoto scan={s} w={132} h={176} /></div>
      <div className="grow" style={{ textAlign: 'left', minWidth: 0 }}>
        <div className={cx('title-s truncate', (!s.abstained || s.resolved) && 'italic')}>{title}</div>
        <div className="caption truncate">{s.zoneLabel ?? 'Outside zones'} · {fmt.relative(s.at)}</div>
      </div>
      <div className="col" style={{ alignItems: 'flex-end', gap: 4 }}>
        <span className="title-s" style={{ color: s.abstained && !s.resolved ? 'var(--wheat-ink)' : 'var(--forest)' }}>{fmt.pct(s.confidence)}</span>
        <span className="mono" style={{ fontSize: 11 }}>{s.id}</span>
      </div>
    </motion.button>
  )
}

/** Zoomed map of where the scan was taken, with the phone's accuracy circle. */
function ScanMap({ field, scan }: { field: FieldParcel; scan: LeafScan }) {
  const at = fromLatLon(field, scan.lat ?? field.lat, scan.lon ?? field.lon)
  const draw = (dc: DrawCtx) => {
    dimOutside(dc, field.boundary, 0.25)
    fieldOutline(dc, field.boundary, { width: 1.6 })
    locationPuck(dc, at, scan.gnssAccuracyM, { label: scan.id })
  }
  return (
    <MapView field={field} surveyed fitBounds={[at.x - 26, at.y - 18, at.x + 26, at.y + 18]} fitPad={0} interactive={false} controls={false} scaleBar animated draw={draw} />
  )
}

/* ------------------------------------------------------------------ cells */

function CellReview({ cells }: { cells: { f: FieldParcel; r: ReviewCellsDto }[] }) {
  const [sent, setSent] = useState<Record<string, boolean>>({})
  const toast = useStore((s) => s.toast)
  if (!cells.length) return <Empty icon={CheckCheck} title="No abstained cells">Every cell in the current prescription was called with confidence.</Empty>
  return (
    <div className="col gap-20">
      <div className="notice notice--slate"><MapPin className="notice__icon" /><div><h4>Abstained cells are not holes in the map</h4><p>Each one is a 2 m square where crop and weed canopy mix and the model would not commit. They are grouped by field so one walk can cover them.</p></div></div>
      <div className="cells-grid">
        {cells.map(({ f, r }) => (
          <CellCard key={f.id} f={f} r={r} sent={!!sent[f.id]} onSend={() => { setSent((x) => ({ ...x, [f.id]: true })); toast(`${r.count} cells listed for scouting on ${f.name}`, { tone: 'ok' }) }} />
        ))}
      </div>
    </div>
  )
}

function CellCard({ f, r, sent, onSend }: { f: FieldParcel; r: ReviewCellsDto; sent: boolean; onSend: () => void }) {
  const g = useGrid(f.id, r.gridSize, r.thresholdPct)
  const draw = (dc: DrawCtx) => { dimOutside(dc, f.boundary, 0.3); if (g) heatLayer(dc, g, f.boundary, 1, false); fieldOutline(dc, f.boundary, { width: 1.6 }) }
  return (
    <Card flush>
      <div style={{ height: 250, position: 'relative' }}><MapView field={f} surveyed interactive={false} controls={false} scaleBar={false} fitPad={20} draw={draw} /></div>
      <div className="card__body">
        <div className="row between"><div><div className="title-m">{f.name}</div><div className="caption">{fmt.plural(r.count, 'cell')} · {fmt.sqm(r.count * r.gridSize * r.gridSize)} to scout</div></div>{g && <Pill tone="wheat">{Math.round((r.count / Math.max(1, g.total)) * 1000) / 10}% of field</Pill>}</div>
        <div className="row gap-8 mt-16">
          <span className="legend-dot" style={{ background: 'var(--heat-abstain)' }} /><span className="caption">Abstained</span>
          <div className="grow" />
          <Button size="sm" variant={sent ? 'tonal' : 'primary'} icon={sent ? Check : Send} disabled={sent} onClick={onSend}>
            {sent ? 'Listed for scouting' : 'Send to scouting'}
          </Button>
        </div>
      </div>
    </Card>
  )
}

/** The leaf photo the phone uploaded, or the procedural leaf when it has not arrived (yet). */
function ScanPhoto({ scan, w, h }: { scan: LeafScan; w: number; h: number }) {
  const { data: src } = useQuery<string>(scan.hasPhoto ? `photo/${scan.id}` : null, () => api.blobUrl(`/scans/${scan.id}/photo`))
  if (src) return <img src={src} alt={`Leaf photo for ${scan.id}`} style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
  return <LeafImage seed={scan.leafSeed} w={w} h={h} />
}
