import { AnimatePresence, motion } from 'framer-motion'
import { CloudUpload, Crosshair, Eye, EyeOff, Grid3x3, Layers, Lock, MapPin, Ruler, X } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { fmt } from '../data/format'
import { dist, fieldAcres, kanalMarla } from '../data/geo'
import { cellAt, cellRef, prescriptionEdges } from '../data/infestation'
import { useGridCompare, useGridStats } from '../data/queries'
import { hasSurvey, isDecider, surveysOf, useStore } from '../data/store'
import { GRID_ACTUATOR, SURVEY_ROLE, WEED_CLASS, type GridCell, type GridSize, type Pt, type TreatmentZone } from '../data/types'
import { MapView, type MapHandle } from '../map/MapView'
import { cellHighlight, dimOutside, fieldOutline, heatLayer, measureLine, pinHit, prescriptionOutline, zonePin, type DrawCtx } from '../map/overlays'
import {
  Button, IconButton, InfoButton, Meter, Pill, Segmented, Slider, Stat, Switch, Tip, ZoneBadge, severityTone, cx, useHotkey,
} from '../ui/kit'
import { useFieldData } from './common'
import './weedmap.css'

type Sel = { kind: 'cell'; cell: GridCell } | { kind: 'zone'; zone: TreatmentZone } | null

export default function WeedMap() {
  const { fieldId } = useParams()
  const nav = useNavigate()
  const initial = useStore((s) => fieldId ?? s.fields.find((f) => hasSurvey(s, f.id))?.id ?? s.fields[0]?.id)
  const { field, surveyed, zones, grid, followGrid, threshold, gridSize } = useFieldData(initial)
  const pushed = useStore((s) => s.pushedThreshold)
  const setThreshold = useStore((s) => s.setThreshold)
  const setGridSize = useStore((s) => s.setGridSize)
  const pushToPhone = useStore((s) => s.pushToPhone)
  const syncing = useStore((s) => s.syncing)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const units = useStore((s) => s.units)
  const fields = useStore((s) => s.fields)
  const decider = useStore((s) => isDecider(s.me))
  const thrMin = useStore((s) => s.station?.thresholdMin ?? 2)
  const thrMax = useStore((s) => s.station?.thresholdMax ?? 40)
  const map = useRef<MapHandle>(null)

  const [view, setView] = useState<'pre' | 'post'>('pre')
  const [layers, setLayers] = useState({ heat: true, outline: true, pins: true, lattice: true, dim: true })
  const [sel, setSel] = useState<Sel>(null)
  const [hover, setHover] = useState<{ cell: GridCell; x: number; y: number } | null>(null)
  const [measure, setMeasure] = useState<{ on: boolean; a?: Pt; b?: Pt }>({ on: false })
  const [collapsed, setCollapsed] = useState(false)

  const list = surveysOf({ seasons, surveys }, field.id)
  const pre = list.find((s) => s.role === 'PRE')
  const post = list.find((s) => s.role === 'PLUS_14D' && s.status === 'READY')
  const activeGrid = view === 'post' && followGrid ? followGrid : grid
  const edges = useMemo(() => (activeGrid ? prescriptionEdges(activeGrid) : new Float32Array()), [activeGrid])
  // the slider reads cheap statistics straight away; the full grid follows once the value settles
  const live = useGridStats(field.id, gridSize, threshold, surveyed)
  const published = useGridStats(field.id, gridSize, pushed, surveyed && threshold !== pushed)
  const compareDto = useGridCompare(field.id, threshold, surveyed)
  const compare = useMemo(() => (compareDto?.grids ?? []).map((x) => ({ g: x.cellMeters as GridSize, f: x.treatedFraction })), [compareDto])
  const delta = live && published ? live.treatedSqm - published.treatedSqm : 0
  const shown = view === 'post' || !live ? activeGrid : live
  const local = units === 'local'
  const ac = field.areaAcres ?? fieldAcres(field)

  useHotkey('m', () => setMeasure((m) => ({ on: !m.on })), [])
  useHotkey('f', () => map.current?.fit(true), [])
  useHotkey('escape', () => { setSel(null); setMeasure({ on: false }) }, [])

  const draw = (dc: DrawCtx) => {
    const { ctx } = dc
    if (layers.dim) dimOutside(dc, field.boundary, 0.3)
    if (activeGrid && layers.heat) heatLayer(dc, activeGrid, field.boundary, 1, layers.lattice)
    if (activeGrid && layers.outline) prescriptionOutline(dc, edges, view === 'post' ? 0.6 : 0.95)
    fieldOutline(dc, field.boundary)
    if (sel?.kind === 'cell' && activeGrid) cellHighlight(dc, activeGrid, sel.cell)
    if (layers.pins && view === 'pre') {
      for (const z of zones) zonePin(dc, z, { size: 28, active: sel?.kind === 'zone' && sel.zone.id === z.id })
    }
    if (layers.pins && view === 'post') {
      for (const z of zones) {
        if (z.efficacyPct == null) continue
        const pct = z.efficacyPct
        zonePin(dc, { ...z, severity: pct >= 70 ? 'CLEAN' : 'HEAVY' }, { size: 34, label: `${pct}` })
      }
    }
    if (measure.a && measure.b) measureLine(dc, measure.a, measure.b, dist(measure.a, measure.b))
    else if (measure.a) { ctx.beginPath(); ctx.arc(dc.cam.sx(measure.a.x), dc.cam.sy(measure.a.y), 5, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill() }
  }

  const onClick = (pt: Pt, s: { x: number; y: number }) => {
    if (measure.on) {
      setMeasure((m) => (!m.a || (m.a && m.b) ? { on: true, a: pt } : { ...m, b: pt }))
      return
    }
    const dc = { cam: map.current!.camera }
    const z = layers.pins ? pinHit(dc, zones, s.x, s.y, 28) : undefined
    if (z) { setSel({ kind: 'zone', zone: z }); return }
    if (activeGrid) {
      const c = cellAt(activeGrid, pt.x, pt.y)
      setSel(c && c.inside ? { kind: 'cell', cell: c } : null)
    }
  }
  const onHover = (pt: Pt | null, s: { x: number; y: number }) => {
    if (!pt || !activeGrid || measure.on) return setHover(null)
    const c = cellAt(activeGrid, pt.x, pt.y)
    setHover(c && c.inside && c.infestPct >= 4 ? { cell: c, x: s.x, y: s.y } : null)
  }

  const panelW = collapsed ? 0 : 372
  return (
    <div className="wm">
      <MapView
        ref={map}
        field={field}
        surveyed={surveyed}
        className="wm__map"
        insets={{ top: 24, right: 24, bottom: 88, left: panelW ? panelW + 36 : 24 }}
        fitPad={28}
        coords
        animated={sel?.kind === 'zone'}
        cursor={measure.on ? 'crosshair' : undefined}
        draw={draw}
        onClick={onClick}
        onHover={onHover}
      >
        {hover && !measure.on && (
          <div className="wm__tip" style={{ left: hover.x + 14, top: hover.y + 14 }}>
            <b>{cellRef(hover.cell.col, hover.cell.row)}</b>
            <span>{hover.cell.abstained ? 'Abstained' : `${Math.round(hover.cell.infestPct)}% cover`}</span>
          </div>
        )}
      </MapView>

      {/* ---------- control panel ---------- */}
      <motion.aside className="wm__panel" initial={{ x: -30, opacity: 0 }} animate={{ x: collapsed ? -400 : 0, opacity: collapsed ? 0 : 1 }} transition={{ type: 'spring', stiffness: 300, damping: 34 }}>
        <div className="wm__scroll">
          <div className="row gap-8">
            <select className="select wm__field" value={field.id} aria-label="Field" onChange={(e) => nav(`/app/weed-map/${e.target.value}`)}>
              {fields.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
            </select>
            <IconButton icon={EyeOff} label="Hide panel" size="sm" onClick={() => setCollapsed(true)} />
          </div>
          <div className="caption" style={{ margin: '8px 2px 0' }}>
            {fmt.area(ac, local)} · {local ? fmt.areaAlt(ac, true) : `${kanalMarla(ac).kanal} kanal`}
            {pre && surveyed && <> · {SURVEY_ROLE.PRE.label.toLowerCase()} flight {fmt.dayMonth(pre.flownAt)} · {pre.images} images</>}
          </div>

          {!surveyed ? (
            <div className="wm__wait">
              <div className="icon-tile icon-tile--slate"><Layers /></div>
              <h4>No processed flight yet</h4>
              <p>{pre ? `The pre-treatment survey is ${pre.status.toLowerCase()} for ${fmt.weekdayShort(pre.flownAt)}.` : 'No survey is planned for this field.'} The weed map appears here as soon as the flight is processed.</p>
              <Link to="/app/flights?upload=1" className="btn btn--tonal btn--sm mt-12">Upload the flight</Link>
            </div>
          ) : (
            <>
              {post && (
                <div className="mt-16">
                  <Segmented block size="sm" value={view} onChange={setView} label="Survey"
                    options={[{ value: 'pre', label: `Pre-treatment · ${fmt.dayMonth(pre!.flownAt)}` }, { value: 'post', label: `+14 d · ${fmt.dayMonth(post.flownAt)}` }]} />
                </div>
              )}

              <div className="wm__sec">
                <div className="row between">
                  <span className="overline row gap-6">Prescription threshold <InfoButton title="Prescription threshold" body={<><p>A cell is put in the prescription if any part of it holds at least this much weed cover. It is the one number that decides how much area is sprayed, so it is set here, by the analyst, and the phones only display it.</p><p>Lower it and small satellite patches join the route; raise it and only the dense cores are sprayed. Every figure on this page recalculates from the survey as you drag.</p></>} /></span>
                  <Pill tone="neutral" icon={Lock}>Phones read-only</Pill>
                </div>
                <div className="wm__thr">
                  <span className="num-xl">{threshold}<small>%</small></span>
                  <span className="caption">of a cell infested</span>
                </div>
                <Slider value={threshold} min={thrMin} max={thrMax} onChange={setThreshold} label="Prescription threshold" />
                <div className="row between caption" style={{ marginTop: -2 }}><span>{thrMin}% · sprays more</span><span>Published {pushed}%</span><span>{thrMax}% · cores only</span></div>
                <AnimatePresence>
                  {threshold !== pushed && (
                    <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} style={{ overflow: 'hidden' }}>
                      <div className="wm__diff">
                        <div className="grow">
                          <b>{delta >= 0 ? '+' : '−'}{fmt.sqm(Math.abs(delta))}</b> vs the {pushed}% phones use now
                        </div>
                        {decider && <Button size="sm" variant="primary" icon={CloudUpload} loading={syncing === 'pushing'} onClick={() => void pushToPhone()}>Publish</Button>}
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>

              {shown && (
                <div className="wm__stats">
                  <Stat sm value={shown.treatedSqm} format={(n) => fmt.sqm(n)} label={view === 'post' ? 'Still above threshold' : 'To spray'} />
                  <Stat sm value={shown.treatedFraction * 100} format={(n) => `${n.toFixed(1)}%`} label="Of the field" />
                  <Stat sm value={zones.length} label="Zones" />
                  <Stat sm value={shown.abstained} label="Abstained cells" tone={shown.abstained ? 'wheat' : undefined} />
                </div>
              )}

              <div className="wm__sec">
                <div className="row between">
                  <span className="overline row gap-6">Spray grid <InfoButton title="Spray grid" body={<><p>The map is cut into square cells because that is what a sprayer can act on. A cell is sprayed if any part of it crosses the threshold, so a coarser grid always sprays more.</p><p>1 m suits a knapsack operator following a phone. 2 m matches boom section control. 5 m absorbs the 3 to 5 m drift of a phone's GNSS.</p></>} /></span>
                </div>
                <Segmented block value={gridSize} onChange={setGridSize} label="Grid size" options={[{ value: 1, label: '1 m' }, { value: 2, label: '2 m' }, { value: 5, label: '5 m' }]} />
                <p className="caption" style={{ marginTop: 8 }}>{GRID_ACTUATOR[gridSize]}</p>
                <div className="col gap-8 mt-12">
                  {compare.map(({ g, f }) => (
                    <div key={g} className="row gap-12">
                      <span className={cx('label', g === gridSize && 'wm__on')} style={{ width: 34 }}>{g} m</span>
                      <div className="grow"><Meter value={f * 3} tone={g === gridSize ? 'forest' : undefined} style={{ opacity: g === gridSize ? 1 : 0.55 }} /></div>
                      <span className="label" style={{ width: 42, textAlign: 'right' }}>{fmt.pct(f)}</span>
                    </div>
                  ))}
                  <p className="caption">Treated area at each resolution. The trade is stated, not hidden.</p>
                </div>
              </div>

              <div className="wm__sec">
                <span className="overline">Layers</span>
                <div className="col gap-4 mt-8">
                  {([['heat', 'Weed cover'], ['outline', 'Prescription outline'], ['pins', 'Zones'], ['lattice', 'Cell lattice'], ['dim', 'Dim outside the parcel']] as const).map(([k, label]) => (
                    <div key={k} className="wm__layer">
                      <span className="body-s" style={{ color: 'var(--ink)' }}>{label}</span>
                      <Switch checked={layers[k]} onChange={(v) => setLayers((l) => ({ ...l, [k]: v }))} label={label} />
                    </div>
                  ))}
                </div>
                <div className="wm__legend">
                  <span><i style={{ background: 'var(--heat-mid)' }} />10–30%</span>
                  <span><i style={{ background: 'var(--heat-high)' }} />Over 30%</span>
                  <span><i style={{ background: 'var(--heat-abstain)' }} />Abstained</span>
                  <span><i className="wm__outline" />In prescription</span>
                </div>
              </div>
            </>
          )}
        </div>
      </motion.aside>
      {collapsed && (
        <motion.button className="wm__reopen" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} onClick={() => setCollapsed(false)}><Eye size={17} /> Show controls</motion.button>
      )}

      {/* ---------- inspector ---------- */}
      <AnimatePresence>
        {sel && (
          <motion.aside className="wm__inspector" initial={{ x: 30, opacity: 0 }} animate={{ x: 0, opacity: 1 }} exit={{ x: 30, opacity: 0 }} transition={{ type: 'spring', stiffness: 320, damping: 32 }}>
            <button className="wm__close" onClick={() => setSel(null)} aria-label="Close"><X size={16} /></button>
            {sel.kind === 'cell' ? <CellInspector cell={sel.cell} size={gridSize} view={view} /> : <ZoneInspector zone={zones.find((z) => z.id === sel.zone.id) ?? sel.zone} fieldId={field.id} view={view} />}
          </motion.aside>
        )}
      </AnimatePresence>

      {/* ---------- toolbar ---------- */}
      <div className="wm__bar">
        <Tip text="Measure distance (M)"><Button variant={measure.on ? 'primary' : 'ghost'} size="sm" icon={Ruler} onClick={() => setMeasure((m) => ({ on: !m.on }))}>
          {measure.on ? (measure.a && measure.b ? `${dist(measure.a, measure.b).toFixed(1)} m` : 'Click two points') : 'Measure'}
        </Button></Tip>
        <span className="vline" />
        <Tip text="Fit field (F)"><Button variant="ghost" size="sm" icon={Crosshair} onClick={() => map.current?.fit(true)}>Fit</Button></Tip>
        <span className="vline" />
        <Tip text="Zones on the map"><span className="row gap-6 body-s" style={{ padding: '0 8px' }}><MapPin size={15} /> {zones.length}</span></Tip>
        <Tip text="Cells in the prescription"><span className="row gap-6 body-s" style={{ padding: '0 8px' }}><Grid3x3 size={15} /> {shown ? fmt.int(shown.flagged) : 0}</span></Tip>
      </div>
    </div>
  )
}

function CellInspector({ cell, size, view }: { cell: GridCell; size: GridSize; view: 'pre' | 'post' }) {
  const sev = cell.infestPct < 10 ? 'CLEAN' : cell.infestPct <= 30 ? 'MODERATE' : 'HEAVY'
  return (
    <>
      <div className="row gap-12">
        <span className="wm__cellref" style={{ background: cell.abstained ? 'var(--heat-abstain)' : cell.infestPct < 10 ? 'var(--sage)' : sev === 'HEAVY' ? 'var(--heat-high)' : 'var(--heat-mid)', color: sev === 'HEAVY' && !cell.abstained ? '#fff' : 'var(--ink)' }}>{cellRef(cell.col, cell.row)}</span>
        <div>
          <div className="title-m">Cell {cellRef(cell.col, cell.row)}</div>
          <div className="caption">{size} m × {size} m · {view === 'post' ? '+14 d survey' : 'pre-treatment'}</div>
        </div>
      </div>
      <div className="row gap-6 mt-16 wrap">
        {cell.abstained ? <Pill tone="wheat">Abstained</Pill> : cell.infestPct < 4 ? <Pill tone="moss">Crop</Pill> : <Pill tone={severityTone(sev)}>{sev === 'HEAVY' ? 'Heavy' : sev === 'MODERATE' ? 'Moderate' : 'Light'}</Pill>}
        <Pill tone={cell.treated ? 'forest' : 'neutral'} dot>{cell.treated ? 'In prescription' : 'Not sprayed'}</Pill>
      </div>
      <div className="wm__grid2 mt-16">
        <Stat sm value={`${Math.round(cell.infestPct)}%`} label="Weed cover" />
        <Stat sm value={cell.infestPct < 4 ? '—' : WEED_CLASS[cell.weedClass].short} label="Predicted class" />
        <Stat sm value={fmt.pct(cell.confidence)} label="Confidence" tone={cell.abstained ? 'wheat' : undefined} />
        <Stat sm value={`${size * size} m²`} label="Cell area" />
      </div>
      <p className="body-s mt-16">{cell.abstained ? 'The model was not confident enough to call this cell. Send a person to look before spraying it.' : cell.infestPct < 4 ? 'Crop canopy. No action indicated.' : WEED_CLASS[cell.weedClass].hint + '.'}</p>
    </>
  )
}

function ZoneInspector({ zone, fieldId, view }: { zone: TreatmentZone; fieldId: string; view: 'pre' | 'post' }) {
  const pct = zone.efficacyPct
  return (
    <>
      <div className="row gap-12">
        <ZoneBadge z={zone} size={42} />
        <div><div className="title-m">{zone.label}</div><div className="caption">{WEED_CLASS[zone.dominantClass].label} · stop {zone.distanceM} m from the previous</div></div>
      </div>
      <div className="row gap-6 mt-16 wrap">
        <Pill tone={severityTone(zone.severity)}>{zone.severity === 'HEAVY' ? 'Heavy' : zone.severity === 'MODERATE' ? 'Moderate' : 'Light'}</Pill>
        <Pill tone={zone.state === 'TREATED' || zone.state === 'RESURVEYED' ? 'forest' : zone.state === 'ROUTED' ? 'slate' : 'neutral'} dot>{zone.state === 'TREATED' || zone.state === 'RESURVEYED' ? 'Treated' : zone.state === 'ROUTED' ? 'On the route' : 'Flagged'}</Pill>
      </div>
      <div className="wm__grid2 mt-16">
        <Stat sm value={fmt.sqm(zone.areaSqm)} label="Zone area" />
        <Stat sm value={`${Math.round(zone.meanInfestPct)}%`} label="Mean cover" />
        <Stat sm value={`${zone.cellCount}`} label="Cells (2 m)" />
        <Stat sm value={pct == null ? '—' : `${pct}%`} label="Control at +14 d" tone={pct == null ? undefined : pct < 70 ? 'clay' : 'forest'} />
      </div>
      {pct != null && pct < 70 && <p className="body-s mt-16" style={{ color: 'var(--clay-ink)' }}>Survivors at label rate. Compare with the rotation history before the next application.</p>}
      <div className="row gap-8 mt-16">
        <Link to={`/app/rotation/${fieldId}`} className="btn btn--secondary btn--sm">Rotation history</Link>
        <Link to={`/app/verification/${fieldId}`} className="btn btn--tonal btn--sm">Verification</Link>
      </div>
      {view === 'post' && <p className="caption mt-12">Pin numbers show percent control.</p>}
    </>
  )
}

