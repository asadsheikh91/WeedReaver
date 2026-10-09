import { motion } from 'framer-motion'
import { Download, FileCode2, FileJson, FolderArchive, Lock } from 'lucide-react'
import { useMemo, useState } from 'react'
import { errorMessage } from '../api/client'
import type { ExportPreviewDto } from '../api/dto'
import { fmt } from '../data/format'
import { useDebounced, useQuery } from '../data/queries'
import { hasSurvey, useStore, type ExportRequest } from '../data/store'
import type { ExportJob, GridSize } from '../data/types'
import { Button, Card, Field, Hairline, Pill, Segmented, SectionHead, SelectInput, Stat, Switch, cx } from '../ui/kit'
import { PageHead } from './common'
import './exports.css'

type Format = ExportJob['format']

const FORMATS: { id: Format; icon: typeof FileJson; title: string; ext: string; body: string }[] = [
  { id: 'GeoJSON', icon: FileJson, title: 'GeoJSON', ext: '.geojson', body: 'Boundary, zones and prescription cells. Opens in QGIS, Google Earth and every web map.' },
  { id: 'Shapefile', icon: FolderArchive, title: 'Shapefile', ext: '.zip', body: 'For desks and older sprayer consoles that still expect .shp, .shx and .dbf.' },
  { id: 'TASKDATA', icon: FileCode2, title: 'ISO 11783-10', ext: 'TASKDATA.XML', body: 'ISOBUS task data for section control on a tractor boom. Zone shapes only; rates are left blank.' },
]

interface Opts { boundary: boolean; zones: boolean; cells: boolean; abstained: boolean }

export default function Exports() {
  const fields = useStore((s) => s.fields)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const threshold = useStore((s) => s.pushedThreshold)
  const gridDefault = useStore((s) => s.gridSize)
  const history = useStore((s) => s.exports)
  const addExport = useStore((s) => s.addExport)
  const downloadExport = useStore((s) => s.downloadExport)
  const previewExport = useStore((s) => s.previewExport)
  const toast = useStore((s) => s.toast)
  const surveyed = fields.filter((f) => hasSurvey({ seasons, surveys }, f.id))
  const [fieldId, setFieldId] = useState(surveyed[0]?.id ?? fields[0]?.id ?? '')
  const [format, setFormat] = useState<Format>('GeoJSON')
  const [size, setSize] = useState<GridSize>(gridDefault)
  const [opts, setOpts] = useState<Opts>({ boundary: true, zones: true, cells: true, abstained: false })
  const [busy, setBusy] = useState(false)
  const [fetching, setFetching] = useState<string | null>(null)

  const field = fields.find((f) => f.id === fieldId) ?? fields[0]
  const req = useDebounced<ExportRequest>(useMemo(() => ({ fieldId: field.id, format, gridSize: size, include: opts }), [field.id, format, size, opts]), 120)
  // the station builds the file; the preview is its first lines, size and counts, without saving anything
  const preview = useQuery<ExportPreviewDto>(surveyed.length ? `export-preview/${JSON.stringify(req)}/${threshold}` : null, () => previewExport(req))
  const pv = preview.data
  const kb = pv ? Math.max(1, Math.round(pv.sizeBytes / 1024)) : 0
  const fmtMeta = FORMATS.find((x) => x.id === format)!
  const lines = pv ? pv.preview.split('\n').slice(0, 30) : []

  const generate = async () => {
    setBusy(true)
    try {
      const job = await addExport(req)
      await downloadExport(job)
      toast(`${job.filename} downloaded`, { tone: 'ok' })
    } catch (e) {
      toast(`Export failed: ${errorMessage(e)}`, { tone: 'warn' })
    }
    setBusy(false)
  }
  const redownload = async (j: ExportJob) => {
    setFetching(j.id)
    try { await downloadExport(j) } catch (e) { toast(`Download failed: ${errorMessage(e)}`, { tone: 'warn' }) }
    setFetching(null)
  }

  return (
    <>
      <PageHead title="Exports" sub="Send the prescription to other tools. In this geography the actuator is usually a person with a knapsack, so the phone route is the primary output; these files are for section control and for the desk." />
      <div className="ex">
        <div className="col gap-20">
          <Card pad>
            <div className="ex__step"><span>1</span> Field and resolution</div>
            <div className="row gap-16 wrap mt-16">
              <div className="grow" style={{ minWidth: 220 }}><Field label="Field"><SelectInput value={field.id} onChange={(e) => setFieldId(e.target.value)}>{surveyed.map((f) => <option key={f.id} value={f.id}>{f.name} · {f.id}</option>)}</SelectInput></Field></div>
              <div className="grow" style={{ minWidth: 260 }}><Field label="Grid resolution" hint="Coarser cells always spray more area."><Segmented block value={size} onChange={setSize} options={[{ value: 1, label: '1 m' }, { value: 2, label: '2 m' }, { value: 5, label: '5 m' }]} /></Field></div>
            </div>
            <div className="ex__lock"><Lock size={15} /> Threshold <b>{threshold}%</b> · set in Settings and published to phones</div>
          </Card>

          <Card pad>
            <div className="ex__step"><span>2</span> Format</div>
            <div className="ex__formats">
              {FORMATS.map((f) => (
                <button key={f.id} className={cx('fmt', format === f.id && 'is-on')} onClick={() => setFormat(f.id)} aria-pressed={format === f.id}>
                  <f.icon size={22} /><b>{f.title}</b><code>{f.ext}</code><span>{f.body}</span>
                </button>
              ))}
            </div>
          </Card>

          <Card pad>
            <div className="ex__step"><span>3</span> Contents</div>
            <div className="col mt-12">
              {([['boundary', 'Field boundary', 'The surveyed parcel polygon'], ['zones', 'Treatment zones', pv ? `${pv.zones} zones in nearest-first route order` : 'Zones in nearest-first route order'], ['cells', 'Prescription cells', `Cells in the prescription at ${size} m`], ['abstained', 'Abstained cells', 'Cells flagged for scouting, not spraying']] as const).map(([k, t, d], i) => (
                <div key={k}>{i > 0 && <Hairline />}
                  <div className="row between" style={{ padding: '12px 0', opacity: format === 'TASKDATA' && k !== 'zones' && k !== 'boundary' ? 0.4 : 1 }}>
                    <div><div className="title-s">{t}</div><div className="caption">{d}</div></div>
                    <Switch label={t} checked={format === 'TASKDATA' ? k === 'zones' || k === 'boundary' : opts[k]} onChange={(v) => format !== 'TASKDATA' && setOpts((o) => ({ ...o, [k]: v }))} />
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </div>

        <div className="ex__side">
          <Card night pad>
            <div className="row between"><span className="overline" style={{ color: 'rgba(245,240,230,.55)' }}>Preview</span><Pill tone="neutral">{pv?.filename ?? '…'}</Pill></div>
            <pre className="ex__code" aria-label="File preview" style={{ opacity: preview.loading ? 0.6 : 1 }}>{preview.error && !pv ? <span>{errorMessage(preview.error)}</span> : lines.map((l, i) => <span key={i}>{l}{'\n'}</span>)}{pv && pv.lines > lines.length && <span className="ex__more">… {fmt.int(pv.lines - lines.length)} more lines</span>}</pre>
          </Card>
          <Card pad>
            <div className="row gap-24">
              <Stat sm value={pv?.zones ?? 0} label="Zones" />
              <Stat sm value={pv?.cells ?? 0} format={fmt.int} label="Cells" />
              <Stat sm value={kb} format={(n) => (n > 900 ? `${(n / 1024).toFixed(1)} MB` : `${Math.round(n)} KB`)} label="File size" />
            </div>
            <Button variant="primary" block size="lg" icon={Download} loading={busy} disabled={!surveyed.length} className="mt-16" onClick={() => void generate()}>Generate {fmtMeta.title}</Button>
            <p className="caption" style={{ marginTop: 10 }}>Rate values are never written to any format. {format === 'GeoJSON' && 'Coordinates are WGS84 longitude, latitude.'}</p>
          </Card>
        </div>
      </div>

      <SectionHead title="Recent exports" />
      <Card flush>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>File</th><th>Field</th><th>Format</th><th>Settings</th><th className="num">Size</th><th>When</th><th /></tr></thead>
            <tbody>
              {history.map((j, i) => (
                <motion.tr key={j.id} initial={i === 0 ? { opacity: 0, backgroundColor: 'rgba(220,230,210,.9)' } : false} animate={{ opacity: 1, backgroundColor: 'rgba(220,230,210,0)' }} transition={{ duration: 1.4 }}>
                  <td><div className="title-s">{j.filename}</div><div className="mono">{j.id}</div></td>
                  <td>{fields.find((f) => f.id === j.fieldId)?.name}</td>
                  <td><Pill tone="neutral">{j.format === 'TASKDATA' ? 'ISO 11783-10' : j.format}</Pill></td>
                  <td className="ink-2" style={{ fontSize: 13 }}>{j.gridSize} m · {j.threshold}% · {j.zones} zones</td>
                  <td className="num">{j.sizeKb} KB</td>
                  <td className="ink-2">{fmt.relative(j.at)}</td>
                  <td className="num"><Button size="sm" variant="ghost" icon={Download} loading={fetching === j.id} onClick={() => void redownload(j)}>Download</Button></td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  )
}
