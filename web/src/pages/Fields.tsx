import { motion } from 'framer-motion'
import { ArrowDown, ArrowUp, Check, FileUp, Plus, Search } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { BoundaryPreviewDto } from '../api/dto'
import { errorMessage } from '../api/client'
import { sampleKml } from '../data/boundaryFile'
import { fmt } from '../data/format'
import { fieldAcres, measureLatLon, toLatLon } from '../data/geo'
import { isDecider, surveysOf, useStore } from '../data/store'
import { DrawFieldMap, type LatLon } from '../map/DrawFieldMap'
import { Button, Card, Chips, Field, Modal, Pill, Stat, Tabs, TextInput, cx } from '../ui/kit'
import { FieldThumb, PageHead, usePortfolio } from './common'
import { FieldStatusPill } from './FieldDetail'
import './fields.css'

type Filter = 'all' | 'spray' | 'flight' | 'done'
type SortKey = 'name' | 'area' | 'zones' | 'flight'

export default function Fields() {
  const nav = useNavigate()
  const portfolio = usePortfolio()
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const units = useStore((s) => s.units)
  const local = units === 'local'
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'name', dir: 1 })
  const [adding, setAdding] = useState(false)
  const decider = useStore((s) => isDecider(s.me))

  const rows = useMemo(() => portfolio.map((p) => {
    const done = p.zones.filter((z) => z.state === 'TREATED' || z.state === 'RESURVEYED').length
    const list = surveysOf({ seasons, surveys }, p.field.id)
    const last = [...list].reverse().find((s) => s.status === 'READY')
    const next = list.find((s) => s.status === 'SCHEDULED')
    const fs = seasons.find((s) => s.fieldId === p.field.id)
    return { ...p, done, last, next, fs, acres: fieldAcres(p.field) }
  }), [portfolio, seasons, surveys])

  const counts = {
    all: rows.length,
    spray: rows.filter((r) => r.surveyed && r.zones.some((z) => z.state !== 'TREATED')).length,
    flight: rows.filter((r) => !r.surveyed).length,
    done: rows.filter((r) => r.surveyed && r.zones.length > 0 && r.zones.every((z) => z.state === 'TREATED')).length,
  }
  const shown = rows
    .filter((r) => (filter === 'all' ? true : filter === 'spray' ? r.surveyed && r.zones.some((z) => z.state !== 'TREATED') : filter === 'flight' ? !r.surveyed : r.surveyed && r.zones.length > 0 && r.zones.every((z) => z.state === 'TREATED')))
    .filter((r) => !q || `${r.field.name} ${r.field.id} ${r.field.village}`.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => {
      const v = sort.key === 'name' ? a.field.name.localeCompare(b.field.name) : sort.key === 'area' ? a.acres - b.acres : sort.key === 'zones' ? a.zones.length - b.zones.length : (a.last?.flownAt ?? 0) - (b.last?.flownAt ?? 0)
      return v * sort.dir
    })

  const th = (key: SortKey, label: string, num?: boolean) => (
    <th className={cx('sortable', num && 'num')} onClick={() => setSort((s) => (s.key === key ? { key, dir: (s.dir * -1) as 1 | -1 } : { key, dir: 1 }))}>
      <span className="row gap-4" style={{ justifyContent: num ? 'flex-end' : undefined }}>{label}{sort.key === key && (sort.dir === 1 ? <ArrowUp size={12} /> : <ArrowDown size={12} />)}</span>
    </th>
  )

  return (
    <>
      <PageHead title="Fields" sub={`${fmt.plural(rows.length, 'parcel')} enrolled in the season. Boundaries and thresholds are defined here; phones receive them on their next sync.`}>
        {decider && <Button variant="primary" icon={Plus} onClick={() => setAdding(true)}>Add field</Button>}
      </PageHead>

      <div className="row between wrap gap-12" style={{ marginBottom: 16 }}>
        <Chips<Filter> value={filter} onChange={setFilter} options={[
          { value: 'all', label: 'All', count: counts.all }, { value: 'spray', label: 'Needs spraying', count: counts.spray },
          { value: 'flight', label: 'Awaiting flight', count: counts.flight }, { value: 'done', label: 'Treated', count: counts.done },
        ]} />
        <div className="input-wrap" style={{ width: 280 }}>
          <Search />
          <TextInput className="input--search" sm placeholder="Search name, id or village" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      </div>

      <Card flush>
        <div className="table-wrap">
          <table className="table table--click">
            <thead>
              <tr>{th('name', 'Field')}{th('area', 'Area', true)}<th>Crop</th>{th('flight', 'Last flight')}{th('zones', 'Zones')}<th>Status</th></tr>
            </thead>
            <tbody>
              {shown.map((r, i) => (
                <motion.tr key={r.field.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.04 }} onClick={() => nav(`/app/fields/${r.field.id}`)}>
                  <td>
                    <div className="row gap-16">
                      <FieldThumb field={r.field} surveyed={r.surveyed} width={104} height={72} />
                      <div>
                        <div className="row gap-8"><span className="title-m">{r.field.name}</span><span className="mono">{r.field.id}</span></div>
                        <div className="body-s">{r.field.village}</div>
                      </div>
                    </div>
                  </td>
                  <td className="num"><div className="title-s">{fmt.area(r.acres, local)}</div><div className="caption">{fmt.areaAlt(r.acres, local)}</div></td>
                  <td><div className="title-s">{r.fs?.crop}</div><div className="caption">{r.fs?.variety}{r.fs?.sowingDate ? ` · sown ${fmt.dayMonth(r.fs.sowingDate)}` : ''}</div></td>
                  <td>
                    {r.last ? <><div className="title-s">{fmt.date(r.last.flownAt)}</div><div className="caption">{r.last.role === 'PRE' ? 'Pre-treatment' : r.last.role === 'PLUS_14D' ? '+14 d follow-up' : '+28 d follow-up'}</div></>
                      : r.next ? <><div className="title-s">{fmt.weekdayShort(r.next.flownAt)}</div><div className="caption">Scheduled · {fmt.inDays(r.next.flownAt)}</div></> : <span className="ink-3">—</span>}
                  </td>
                  <td style={{ minWidth: 150 }}>
                    {r.zones.length ? <div className="col gap-6"><span className="title-s">{r.done}/{r.zones.length} treated</span><div className="fields__bar"><i style={{ width: `${(r.done / r.zones.length) * 100}%` }} /></div></div> : <span className="ink-3">{r.surveyed ? 'None above threshold' : '—'}</span>}
                  </td>
                  <td><FieldStatusPill fieldId={r.field.id} /></td>
                </motion.tr>
              ))}
              {shown.length === 0 && <tr><td colSpan={6}><div className="empty"><h4>No fields match</h4><p>Clear the search or pick another filter.</p></div></td></tr>}
            </tbody>
          </table>
        </div>
      </Card>

      <AddFieldModal open={adding} onClose={() => setAdding(false)} />
    </>
  )
}

/* ------------------------------------------------------------------ add field */

function AddFieldModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const nav = useNavigate()
  const parse = useStore((s) => s.parseBoundary)
  const addField = useStore((s) => s.addField)
  const toast = useStore((s) => s.toast)
  const [parsed, setParsed] = useState<{ preview: BoundaryPreviewDto; source: string } | null>(null)
  const [name, setName] = useState('')
  const [village, setVillage] = useState('Pindi Bhattian, Hafizabad')
  const [over, setOver] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const [mode, setMode] = useState<'map' | 'file'>('map')
  const [ring, setRing] = useState<LatLon[]>([])
  const fields = useStore((s) => s.fields)
  const existing = useMemo(() => fields.map((f) => ({ name: f.name, ring: f.boundary.map((p) => { const [lat, lon] = toLatLon(f, p); return { lat, lon } }) })), [fields])
  const center = fields[0] ? { lat: fields[0].lat, lon: fields[0].lon } : { lat: 31.8942, lon: 73.2711 }
  const drawn = measureLatLon(ring)

  const reset = () => { setParsed(null); setName(''); setError(''); setRing([]) }
  const close = () => { reset(); onClose() }
  // the server reads the file (KML, GeoJSON or CSV), validates the ring and measures it
  const load = async (file: File | undefined) => {
    if (!file) return
    setBusy(true)
    try {
      const preview = await parse(file)
      setError(''); setParsed({ preview, source: file.name })
      if (preview.name && !name) setName(preview.name.slice(0, 32))
    } catch (e) {
      setError(errorMessage(e))
    }
    setBusy(false)
  }
  const sampleFile = () => new File([sampleKml()], 'station-plot-register.kml', { type: 'application/vnd.google-earth.kml+xml' })
  const useSample = () => void load(sampleFile())
  const downloadSample = () => {
    const url = URL.createObjectURL(sampleFile())
    const a = document.createElement('a'); a.href = url; a.download = 'station-plot-register.kml'; a.click(); URL.revokeObjectURL(url)
  }
  const create = async () => {
    const boundaryLatLon = mode === 'map' ? ring : parsed?.preview.boundaryLatLon
    if (!boundaryLatLon) return
    setBusy(true)
    try {
      const f = await addField({ name: name.trim(), village, boundaryLatLon, captureMethod: mode === 'map' ? 'Drawn' : 'Imported' })
      close(); toast(`${f.name} added. A pre-treatment flight is scheduled.`, { tone: 'ok' }); nav(`/app/fields/${f.id}`)
    } catch (e) {
      setError(errorMessage(e))
    }
    setBusy(false)
  }
  const ac = parsed ? parsed.preview.area.acres : 0

  return (
    <Modal open={open} onClose={close} wide title="Add a field" sub="Click the field's corners on the satellite map, or import a boundary file. Walked boundaries are captured on the phone."
      footer={mode === 'map' ? (
        <>
          <Button variant="ghost" disabled={!ring.length} onClick={() => setRing(ring.slice(0, -1))}>Undo corner</Button>
          <Button variant="ghost" disabled={!ring.length} onClick={() => setRing([])}>Clear</Button>
          <div className="grow" />
          <Button variant="primary" disabled={!name.trim() || ring.length < 3 || !!drawn.crosses} loading={busy} icon={Check} onClick={() => void create()}>Add field</Button>
        </>
      ) : parsed ? (
        <>
          <Button variant="ghost" onClick={reset}>Choose another file</Button>
          <Button variant="primary" disabled={!name.trim()} loading={busy} icon={Check} onClick={() => void create()}>Add field</Button>
        </>
      ) : <Button variant="secondary" onClick={close}>Cancel</Button>}>
      <Tabs value={mode} onChange={(v) => { setMode(v); setError('') }} options={[{ value: 'map', label: 'Draw on map' }, { value: 'file', label: 'Import a file' }]} />
      <div style={{ height: 16 }} />
      {mode === 'map' ? (
        <div className="col gap-16" style={{ paddingBottom: 8 }}>
          <DrawFieldMap ring={ring} onChange={setRing} existing={existing} center={center} />
          <div className="row gap-24 wrap">
            <Stat sm value={drawn.acres} format={(n) => n.toFixed(2)} unit="ac" label={fmt.areaAlt(drawn.acres, true)} />
            <Stat sm value={fmt.meters(drawn.perimeterM)} label="Perimeter" />
            <Stat sm value={String(ring.length)} label="Corners" />
            <div className="grow" style={{ minWidth: 220 }}>
              <Field label="Field name"><TextInput value={name} onChange={(e) => setName(e.target.value.slice(0, 32))} placeholder="e.g. Tubewell killa" /></Field>
            </div>
            <div className="grow" style={{ minWidth: 220 }}>
              <Field label="Village"><TextInput value={village} onChange={(e) => setVillage(e.target.value.slice(0, 48))} /></Field>
            </div>
          </div>
          {drawn.crosses && <div className="notice notice--clay"><div><h4>The outline crosses itself. Undo the last corner or drag a corner to fix it.</h4></div></div>}
          {ring.length > 0 && ring.length < 3 && <span className="caption">Keep clicking corners: a field needs at least three.</span>}
          {error && <div className="notice notice--clay"><div><h4>{error}</h4></div></div>}
        </div>
      ) : !parsed ? (
        <div className="col gap-16" style={{ paddingBottom: 8 }}>
          <div
            className={cx('drop', over && 'drop--over')} role="button" tabIndex={0}
            onClick={() => input.current?.click()} onKeyDown={(e) => e.key === 'Enter' && input.current?.click()}
            onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
            onDrop={(e) => { e.preventDefault(); setOver(false); void load(e.dataTransfer.files[0]) }}
          >
            <div className="icon-tile">{busy ? <span className="spin" /> : <FileUp />}</div>
            <div className="title-s">{busy ? 'Reading the boundary…' : 'Drop a KML or GeoJSON file'}</div>
            <div className="body-s">or click to browse · one polygon, WGS84 · CSV of lat, lon also works</div>
            <input ref={input} type="file" accept=".kml,.geojson,.json,.csv,application/json,application/vnd.google-earth.kml+xml,text/csv" hidden onChange={(e) => { void load(e.target.files?.[0]); e.target.value = '' }} />
          </div>
          {error && <div className="notice notice--clay"><div><h4>{error}</h4></div></div>}
          <div className="row gap-12 wrap">
            <Button size="sm" variant="tonal" disabled={busy} onClick={useSample}>Use a sample plot</Button>
            <Button size="sm" variant="ghost" onClick={downloadSample}>Download the sample KML</Button>
          </div>
        </div>
      ) : (
        <div className="col gap-16" style={{ paddingBottom: 8 }}>
          <Card pad style={{ background: 'var(--sage-tint)', borderColor: 'var(--sage-line)' }}>
            <div className="row gap-24">
              <Stat sm value={ac} format={(n) => n.toFixed(2)} unit="ac" label={fmt.areaAlt(ac, true)} />
              <Stat sm value={fmt.meters(parsed.preview.area.perimeterM)} label="Perimeter" />
              <Stat sm value={String(parsed.preview.vertices)} label="Vertices" />
              <div className="grow" />
              <Pill tone="forest" icon={Check}>{parsed.source}</Pill>
            </div>
          </Card>
          <Field label="Field name"><TextInput autoFocus value={name} onChange={(e) => setName(e.target.value.slice(0, 32))} placeholder="e.g. Tubewell killa" /></Field>
          <Field label="Village"><TextInput value={village} onChange={(e) => setVillage(e.target.value.slice(0, 48))} /></Field>
          {error && <div className="notice notice--clay"><div><h4>{error}</h4></div></div>}
          <p className="caption">Geometry is stored as a polygon and clipped at analysis time. Nothing is a cropped basemap tile.</p>
        </div>
      )}
    </Modal>
  )
}

