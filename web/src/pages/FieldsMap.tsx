import { AnimatePresence, motion } from 'framer-motion'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import {
  AlertTriangle, ArrowLeft, Check, Download, Eye, EyeOff, Focus, ImageOff, Layers, MapPin, PanelLeftClose, PanelLeftOpen,
  Plus, Search, Sprout, Upload,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { OrthomosaicDto } from '../api/dto'
import { fmt } from '../data/format'
import { centroid, fieldAcres, toLatLon } from '../data/geo'
import { hasSurvey, isDecider, surveysOf, useStore } from '../data/store'
import { SURVEY_ROLE, type FieldParcel, type Survey } from '../data/types'
import { satellite, streets } from '../map/basemaps'
import { orthoBounds, orthoLayer } from '../map/orthoLayer'
import { Button, Empty, IconButton, Meter, Notice, Pill, Slider, Switch, TextInput, cx } from '../ui/kit'
import { AddFieldModal } from './Fields'
import { UploadModal } from './Flights'
import './fieldsmap.css'

/*
 * Every field the user may see, on satellite imagery, with the orthomosaic of its latest processed flight laid
 * over it. Select a field to follow its flights: upload images (analysts and administrators), watch the
 * photogrammetry run, then inspect the stitched result tile by tile.
 */

type MapStatus = 'mapped' | 'processing' | 'failed' | 'awaiting'
const STATUS: Record<MapStatus, { label: string; tone: 'forest' | 'slate' | 'clay' | 'wheat' }> = {
  mapped: { label: 'Imagery ready', tone: 'forest' },
  processing: { label: 'Processing', tone: 'slate' },
  failed: { label: 'Processing failed', tone: 'clay' },
  awaiting: { label: 'Awaiting images', tone: 'wheat' },
}
const PANEL_W = 372
const LABEL_ZOOM = 14

interface Row {
  field: FieldParcel
  ring: L.LatLngTuple[]
  centre: L.LatLngTuple
  acres: number
  status: MapStatus
  live?: Survey
  failed?: Survey
  /** Flights with a stitched orthomosaic, newest first. */
  orthos: Survey[]
}

function rowFor(field: FieldParcel, list: Survey[]): Row {
  const ring = field.boundary.map((p) => toLatLon(field, p) as L.LatLngTuple)
  const c = centroid(field.boundary)
  const live = list.find((s) => s.status === 'QUEUED' || s.status === 'PROCESSING')
  const orthos = list.filter((s) => s.hasOrthomosaic).sort((a, b) => b.flownAt - a.flownAt)
  const lastRun = [...list].filter((s) => s.status !== 'SCHEDULED').sort((a, b) => (b.processedAt ?? b.flownAt) - (a.processedAt ?? a.flownAt))[0]
  const failed = lastRun?.status === 'FAILED' ? lastRun : undefined
  const status: MapStatus = live ? 'processing' : failed && !orthos.length ? 'failed' : orthos.length ? 'mapped' : 'awaiting'
  return { field, ring, centre: toLatLon(field, c) as L.LatLngTuple, acres: field.areaAcres ?? fieldAcres(field), status, live, failed, orthos }
}

/* ------------------------------------------------------------------ orthomosaic descriptions */

type InfoState = OrthomosaicDto | { error: string }
const infoCache = new Map<string, Promise<OrthomosaicDto>>()
const infoKey = (s: Survey) => `${s.id}@${s.processedAt ?? 0}`

/** Where each flight's mosaic lies and how to tile it, fetched once per processing of the flight. */
function useOrthoInfos(flights: Survey[]) {
  const [infos, setInfos] = useState<Record<string, InfoState>>({})
  const keys = flights.map(infoKey).join(',')
  useEffect(() => {
    let alive = true
    for (const s of flights) {
      const key = infoKey(s)
      if (infos[key]) continue
      let p = infoCache.get(key)
      if (!p) {
        p = api.get<OrthomosaicDto>(`/surveys/${s.id}/orthomosaic/info`)
        infoCache.set(key, p)
        p.catch(() => infoCache.delete(key)) // let a later visit try again
      }
      p.then((d) => { if (alive) setInfos((m) => ({ ...m, [key]: d })) })
        .catch((e: unknown) => { if (alive) setInfos((m) => ({ ...m, [key]: { error: errorMessage(e) } })) })
    }
    return () => { alive = false }
  }, [keys]) // the flights' identities, not the array's
  return useCallback((s: Survey | undefined): InfoState | undefined => (s ? infos[infoKey(s)] : undefined), [infos])
}
const isInfo = (x: InfoState | undefined): x is OrthomosaicDto => !!x && !('error' in x)

/* ------------------------------------------------------------------ page */

export default function FieldsMap() {
  const [params, setParams] = useSearchParams()
  const fields = useStore((s) => s.fields)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const units = useStore((s) => s.units)
  const decider = useStore((s) => isDecider(s.me))
  const local = units === 'local'

  const rows = useMemo(() => fields.map((f) => rowFor(f, surveysOf({ seasons, surveys }, f.id))), [fields, seasons, surveys])
  const selectedId = params.get('field')
  const selected = rows.find((r) => r.field.id === selectedId)
  const flightParam = params.get('flight')
  /** The flight whose mosaic each field shows: the one picked for the selected field, else its newest. */
  const shownFor = useCallback((r: Row) => (r.field.id === selectedId && r.orthos.find((s) => s.id === flightParam)) || r.orthos[0], [selectedId, flightParam])
  const shown = useMemo(() => rows.map(shownFor).filter((s): s is Survey => !!s), [rows, shownFor])
  const infoOf = useOrthoInfos(shown)

  const [q, setQ] = useState('')
  const [opacity, setOpacity] = useState(1)
  const [imagery, setImagery] = useState(true)
  const [adding, setAdding] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [panelOpen, setPanelOpen] = useState(true)
  const [zoom, setZoom] = useState(0)

  const el = useRef<HTMLDivElement>(null)
  const map = useRef<L.Map | null>(null)
  const shapes = useRef<L.LayerGroup | null>(null)
  const mosaics = useRef(new Map<string, L.GridLayer>())
  const fitted = useRef(false)
  const insetLeft = panelOpen ? PANEL_W + 32 : 24
  const pad = useCallback((): L.FitBoundsOptions => ({ paddingTopLeft: [insetLeft, 32], paddingBottomRight: [72, 32] }), [insetLeft])

  const select = useCallback((id: string | null, flight?: string) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (id) next.set('field', id); else next.delete('field')
      if (flight) next.set('flight', flight); else next.delete('flight')
      return next
    }, { replace: true })
  }, [setParams])

  // ---- the map, built once ----------------------------------------------------------------------
  useEffect(() => {
    const sat = satellite()
    const m = L.map(el.current!, { zoomControl: false, maxZoom: 23, layers: [sat], worldCopyJump: true })
    m.setView([30.4, 71.5], 6) // Punjab, until the fields are fitted
    L.control.layers({ Satellite: sat, Street: streets() }, undefined, { position: 'topright' }).addTo(m)
    L.control.zoom({ position: 'bottomright' }).addTo(m)
    L.control.scale({ position: 'bottomright', imperial: false }).addTo(m)
    shapes.current = L.layerGroup().addTo(m)
    m.on('zoomend', () => setZoom(m.getZoom()))
    setZoom(m.getZoom())
    map.current = m
    const layers = mosaics.current
    const ro = new ResizeObserver(() => m.invalidateSize())
    ro.observe(el.current!)
    return () => { ro.disconnect(); m.remove(); map.current = null; shapes.current = null; layers.clear(); fitted.current = false }
  }, [])

  // ---- fields: outline, pin and name --------------------------------------------------------------
  const look = rows.map((r) => `${r.field.id}:${r.status}:${r.field.name}:${r.ring.length}`).join('|')
  useEffect(() => {
    const g = shapes.current
    if (!g) return
    g.clearLayers()
    for (const r of rows) {
      const on = r.field.id === selectedId
      const tone = `var(--${STATUS[r.status].tone === 'forest' ? 'moss' : STATUS[r.status].tone})`
      const hasPicture = imagery && r.orthos.length > 0
      L.polygon(r.ring, {
        color: on ? '#FFF6DE' : '#FFFFFF', weight: on ? 3 : 1.6, opacity: on ? 1 : 0.85, dashArray: hasPicture || on ? undefined : '5 5',
        fillColor: '#FFF6DE', fillOpacity: hasPicture ? 0 : on ? 0.16 : 0.08, className: 'fm-shape',
      }).on('click', () => select(r.field.id)).addTo(g)
      L.marker(r.centre, {
        icon: L.divIcon({
          className: '', iconSize: [30, 38], iconAnchor: [15, 36],
          html: `<span class="fm-pin${on ? ' is-on' : ''}" style="--pin:${tone}"><i></i></span>`,
        }),
        keyboard: true, title: r.field.name, riseOnHover: true, zIndexOffset: on ? 1000 : 0,
      }).bindTooltip(r.field.name, { permanent: true, direction: 'bottom', offset: [0, 2], className: on ? 'fm-label fm-label--on' : 'fm-label' })
        .on('click', () => select(r.field.id)).addTo(g)
    }
  }, [look, selectedId, imagery]) // what the shapes look like, not every progress tick

  // ---- orthomosaics -------------------------------------------------------------------------------
  useEffect(() => {
    const m = map.current
    if (!m) return
    const want = new Map<string, OrthomosaicDto>()
    if (imagery) for (const s of shown) { const i = infoOf(s); if (isInfo(i)) want.set(`${i.surveyId}@${i.version}`, i) }
    for (const [key, layer] of mosaics.current) if (!want.has(key)) { layer.remove(); mosaics.current.delete(key) }
    for (const [key, info] of want) if (!mosaics.current.has(key)) mosaics.current.set(key, orthoLayer(info, opacity).addTo(m))
  }, [shown, infoOf, imagery]) // opacity is applied to existing layers below
  useEffect(() => { for (const layer of mosaics.current.values()) layer.setOpacity(opacity) }, [opacity])

  // ---- camera: fit every field once, then fly to whichever field is selected ------------------------
  const flyTo = useCallback((b: L.LatLngBounds) => map.current?.flyToBounds(b, { ...pad(), maxZoom: 19, duration: 0.9 }), [pad])
  const lastFlown = useRef<string | null>(null)
  useEffect(() => {
    const m = map.current
    if (!m || !rows.length) return
    const id = selected?.field.id ?? null
    if (!fitted.current) {
      fitted.current = true
      lastFlown.current = id
      m.fitBounds(L.latLngBounds(selected ? selected.ring : rows.flatMap((r) => r.ring)), { ...pad(), maxZoom: 18, animate: false })
    } else if (id !== lastFlown.current) {
      lastFlown.current = id
      if (selected) flyTo(L.latLngBounds(selected.ring))
    }
  }, [rows, selected, pad, flyTo])
  useEffect(() => { map.current?.invalidateSize() }, [panelOpen])

  const visible = rows.filter((r) => !q || `${r.field.name} ${r.field.id} ${r.field.village}`.toLowerCase().includes(q.toLowerCase()))
  const counts = { mapped: rows.filter((r) => r.orthos.length).length, processing: rows.filter((r) => r.status === 'processing').length }
  const centre = map.current?.getCenter()

  return (
    <div className={cx('fm', zoom >= LABEL_ZOOM && 'fm--labels')}>
      <div ref={el} className="fm__map" />

      <AnimatePresence initial={false}>
        {panelOpen ? (
          <motion.aside key="panel" className="fm__panel" style={{ width: PANEL_W }} initial={{ opacity: 0, x: -16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -16 }} transition={{ duration: 0.22 }}>
            {selected ? (
              <FieldPanel row={selected} shown={shownFor(selected)} info={infoOf(shownFor(selected))} local={local} decider={decider}
                opacity={opacity} setOpacity={setOpacity} imagery={imagery} setImagery={setImagery}
                onBack={() => select(null)} onPick={(s) => select(selected.field.id, s.id)} onUpload={() => setUploading(true)}
                onFit={(b) => flyTo(b)} onCollapse={() => setPanelOpen(false)} />
            ) : (
              <>
                <div className="fm__head">
                  <div className="grow">
                    <h1 className="fm__title">Map</h1>
                    <p className="body-s">{fmt.plural(rows.length, 'field')} · {counts.mapped} with imagery{counts.processing ? ` · ${counts.processing} processing` : ''}</p>
                  </div>
                  <IconButton icon={PanelLeftClose} label="Hide the panel" size="sm" onClick={() => setPanelOpen(false)} />
                </div>
                <div className="fm__tools">
                  <div className="input-wrap grow">
                    <Search />
                    <TextInput className="input--search" sm placeholder="Search name, id or village" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search fields" />
                  </div>
                  {decider && <Button size="sm" variant="primary" icon={Plus} onClick={() => setAdding(true)}>Add field</Button>}
                </div>
                <div className="fm__scroll">
                  {rows.length === 0 ? (
                    <Empty icon={Sprout} title="No fields yet" action={decider ? <Button variant="primary" icon={Plus} onClick={() => setAdding(true)}>Add your first field</Button> : undefined}>
                      {decider ? 'Find the place on the map, click the corners of the field, and give it a name.' : 'No fields are assigned to you yet. An analyst adds fields and assigns them.'}
                    </Empty>
                  ) : visible.length === 0 ? (
                    <Empty icon={Search} title="No fields match">Clear the search to see every field.</Empty>
                  ) : (
                    <ul className="fm__list">
                      {visible.map((r) => <FieldRow key={r.field.id} row={r} local={local} onClick={() => select(r.field.id)} />)}
                    </ul>
                  )}
                </div>
                <div className="fm__legend">
                  {(Object.keys(STATUS) as MapStatus[]).map((k) => <span key={k}><i style={{ background: `var(--${STATUS[k].tone === 'forest' ? 'moss' : STATUS[k].tone})` }} />{STATUS[k].label}</span>)}
                </div>
              </>
            )}
          </motion.aside>
        ) : (
          <motion.button key="reopen" className="fm__reopen" onClick={() => setPanelOpen(true)} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <PanelLeftOpen size={16} /> {selected ? selected.field.name : 'Fields'}
          </motion.button>
        )}
      </AnimatePresence>

      {decider && <AddFieldModal open={adding} onClose={() => setAdding(false)} onCreated={(f) => select(f.id)}
        center={centre && map.current && map.current.getZoom() >= 12 ? { lat: centre.lat, lon: centre.lng } : undefined} />}
      {decider && selected && <UploadModal open={uploading} onClose={() => setUploading(false)} fieldId={selected.field.id} />}
    </div>
  )
}

/* ------------------------------------------------------------------ list */

function StatusText({ row }: { row: Row }) {
  if (row.status === 'processing' && row.live) return <span className="fm__st fm__st--slate">{row.live.status === 'QUEUED' ? 'Queued' : `${Math.round(row.live.progress * 100)}%`}</span>
  if (row.status === 'failed') return <span className="fm__st fm__st--clay">Failed</span>
  if (row.orthos.length) return <span className="fm__st fm__st--forest">{fmt.dayMonth(row.orthos[0].flownAt)}</span>
  return <span className="fm__st fm__st--wheat">No imagery</span>
}

function FieldRow({ row, local, onClick }: { row: Row; local: boolean; onClick: () => void }) {
  const tone = STATUS[row.status].tone
  return (
    <li>
      <button className="fm__row" onClick={onClick}>
        <span className="fm__dot" style={{ background: `var(--${tone === 'forest' ? 'moss' : tone})` }} />
        <span className="grow" style={{ minWidth: 0 }}>
          <span className="fm__name">{row.field.name}</span>
          <span className="fm__meta">{row.field.village} · {fmt.area(row.acres, local)}</span>
        </span>
        <StatusText row={row} />
      </button>
      {row.status === 'processing' && row.live && <Meter value={row.live.progress} tone="slate" style={{ margin: '0 14px 10px 34px' }} />}
    </li>
  )
}

/* ------------------------------------------------------------------ one field */

function FieldPanel({ row, shown, info, local, decider, opacity, setOpacity, imagery, setImagery, onBack, onPick, onUpload, onFit, onCollapse }: {
  row: Row; shown?: Survey; info?: InfoState; local: boolean; decider: boolean
  opacity: number; setOpacity: (v: number) => void; imagery: boolean; setImagery: (v: boolean) => void
  onBack: () => void; onPick: (s: Survey) => void; onUpload: () => void; onFit: (b: L.LatLngBounds) => void; onCollapse: () => void
}) {
  const f = row.field
  const surveyed = useStore((s) => hasSurvey(s, f.id))
  const [downloading, setDownloading] = useState(false)
  const download = async () => {
    if (!isInfo(info)) return
    setDownloading(true)
    try { await api.download(info.downloadUrl, `${info.surveyId}_orthomosaic.tif`) } catch (e) { useStore.getState().toast(`Download failed: ${errorMessage(e)}`, { tone: 'warn' }) }
    setDownloading(false)
  }
  return (
    <>
      <div className="fm__head">
        <IconButton icon={ArrowLeft} label="All fields" size="sm" onClick={onBack} />
        <div className="grow" style={{ minWidth: 0 }}>
          <div className="row gap-8"><h1 className="fm__title fm__title--s">{f.name}</h1><span className="mono">{f.id}</span></div>
          <p className="body-s">{f.village} · {fmt.area(row.acres, local)}</p>
        </div>
        <IconButton icon={PanelLeftClose} label="Hide the panel" size="sm" onClick={onCollapse} />
      </div>
      <div className="fm__scroll">
        <div className="row gap-8 wrap">
          <Pill tone={STATUS[row.status].tone} dot={row.status === 'processing'} icon={row.status === 'mapped' ? Check : undefined}>{STATUS[row.status].label}</Pill>
          <Button size="sm" variant="ghost" icon={Focus} onClick={() => onFit(L.latLngBounds(row.ring))}>Zoom to field</Button>
        </div>

        {row.live && <LiveRun s={row.live} />}

        {row.failed && !row.live && (
          <div className="fm__sec">
            <Notice tone="clay" icon={AlertTriangle} title={`${SURVEY_ROLE[row.failed.role].label} flight failed`}
              action={decider ? <div className="mt-12"><Button size="sm" icon={Upload} onClick={onUpload}>Upload images again</Button></div> : undefined}>
              {row.failed.error ?? 'The flight could not be processed.'}
            </Notice>
          </div>
        )}

        {row.orthos.length > 0 ? (
          <div className="fm__sec">
            <div className="row between"><h3 className="fm__h">Orthomosaic</h3><Switch checked={imagery} onChange={setImagery} label="Show imagery" /></div>
            <ul className="fm__flights">
              {row.orthos.map((s) => (
                <li key={s.id}>
                  <button className={cx('fm__flight', s.id === shown?.id && 'is-on')} onClick={() => onPick(s)} aria-pressed={s.id === shown?.id}>
                    <span className="fm__radio" />
                    <span className="grow">
                      <span className="fm__name">{SURVEY_ROLE[s.role].label}</span>
                      <span className="fm__meta">Flown {fmt.date(s.flownAt)} · {fmt.int(s.images)} images{s.gsdCm ? ` · ${s.gsdCm} cm/px` : ''}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            {info && !isInfo(info) && <Notice tone="clay" icon={ImageOff} title="The orthomosaic could not be loaded">{info.error}</Notice>}
            {isInfo(info) && (
              <>
                <div className="fm__opacity">
                  <span className="caption">Opacity</span>
                  <Slider value={Math.round(opacity * 100)} min={0} max={100} step={5} onChange={(v) => setOpacity(v / 100)} label="Imagery opacity" />
                  <span className="mono">{Math.round(opacity * 100)}%</span>
                </div>
                <dl className="fm__facts">
                  <div><dt>Resolution</dt><dd>{info.gsdCm} cm/px</dd></div>
                  <div><dt>Size</dt><dd>{fmt.int(info.width)} × {fmt.int(info.height)} px</dd></div>
                  <div><dt>Processed</dt><dd>{info.processedAt ? fmt.date(Date.parse(info.processedAt)) : '—'}</dd></div>
                  <div><dt>GeoTIFF</dt><dd>{fmt.bytes(info.sizeBytes)}{info.epsg ? ` · EPSG:${info.epsg}` : ''}</dd></div>
                </dl>
                <div className="row gap-8 wrap">
                  <Button size="sm" icon={Focus} onClick={() => onFit(orthoBounds(info))}>Zoom to imagery</Button>
                  <Button size="sm" variant="ghost" icon={Download} loading={downloading} onClick={() => void download()}>Download GeoTIFF</Button>
                </div>
              </>
            )}
            {!info && imagery && <p className="caption fm__loading"><span className="spin" /> Loading imagery…</p>}
            {!imagery && <p className="caption"><EyeOff size={13} /> Imagery hidden</p>}
          </div>
        ) : !row.live && !row.failed && (
          <div className="fm__sec">
            <Empty icon={MapPin} title="No imagery yet" action={decider ? <Button variant="primary" icon={Upload} onClick={onUpload}>Upload flight images</Button> : undefined}>
              {decider
                ? 'Upload the photos from the drone’s SD card. OpenDroneMap stitches them into an orthomosaic that appears here.'
                : 'An analyst uploads the flight’s images; the orthomosaic appears here once it has been stitched.'}
            </Empty>
          </div>
        )}

        <div className="fm__sec fm__actions">
          {decider && (row.orthos.length > 0 || row.failed) && !row.live && <Button size="sm" variant="secondary" icon={Upload} onClick={onUpload}>Upload images</Button>}
          <Link to={`/app/fields/${f.id}`} className="btn btn--ghost btn--sm"><Eye /> Field page</Link>
          {surveyed && <Link to={`/app/weed-map/${f.id}`} className="btn btn--ghost btn--sm"><Layers /> Weed map</Link>}
        </div>
      </div>
    </>
  )
}

const RUN_STAGES = ['Queued', 'Photogrammetry', 'Segmentation', 'Zoning'] as const

function LiveRun({ s }: { s: Survey }) {
  const at = s.status === 'QUEUED' ? 0 : Math.max(1, RUN_STAGES.indexOf((s.stage ?? 'Photogrammetry') as (typeof RUN_STAGES)[number]))
  return (
    <div className="fm__sec">
      <div className="row between"><h3 className="fm__h">{SURVEY_ROLE[s.role].label} flight</h3><span className="num-m">{Math.round(s.progress * 100)}%</span></div>
      <Meter value={s.progress} tone="slate" lg />
      <ol className="fm__stages">
        {RUN_STAGES.map((st, i) => <li key={st} className={cx(i < at && 'is-done', i === at && 'is-now')}>{i < at ? <Check size={12} /> : <span />}{st === 'Photogrammetry' ? 'Stitching (OpenDroneMap)' : st}</li>)}
      </ol>
      <p className="caption">{fmt.int(s.images)} images. Stitching takes from a few minutes to an hour, depending on the flight; you can leave this page.</p>
    </div>
  )
}
