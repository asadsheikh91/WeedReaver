import { motion } from 'framer-motion'
import { ArrowRight, Check, Cpu, FileImage, Layers, PlaneTakeoff, Upload } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fmt } from '../data/format'
import { errorMessage } from '../api/client'
import { hasSurvey, isDecider, useStore } from '../data/store'
import { SURVEY_ROLE, SURVEY_STATUS, type Survey, type SurveyRole } from '../data/types'
import { Button, Card, Field, Meter, Modal, Pill, Ring, SectionHead, SelectInput, Segmented, Stat, cx } from '../ui/kit'
import { FieldThumb, PageHead } from './common'
import './fields.css'
import './flights.css'

const STAGES = ['Upload', 'Photogrammetry', 'Segmentation', 'Zoning'] as const
/** Where each stage starts and ends in the job's progress, as the station's pipeline reports it. */
const STAGE_FROM = [0, 0.05, 0.6, 0.9], STAGE_TO = [0.05, 0.6, 0.9, 1]
const stageIndex = (s: Survey) => {
  const named = ({ Queued: 0, Preparing: 0, Photogrammetry: 1, Segmentation: 2, Zoning: 3 } as Record<string, number>)[s.stage ?? '']
  return named ?? (s.progress < 0.05 ? 0 : s.progress < 0.6 ? 1 : s.progress < 0.9 ? 2 : 3)
}

export default function Flights() {
  const [params, setParams] = useSearchParams()
  const fields = useStore((s) => s.fields)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const modelAerial = useStore((s) => s.station?.modelAerial ?? '')
  const decider = useStore((s) => isDecider(s.me))
  const [open, setOpen] = useState(params.get('upload') === '1')

  useEffect(() => { if (params.get('upload') === '1') setOpen(true) }, [params])
  const close = () => { setOpen(false); if (params.has('upload')) { params.delete('upload'); setParams(params, { replace: true }) } }

  const rows = useMemo(() => surveys.map((s) => {
    const fieldId = s.fieldId ?? seasons.find((x) => x.id === s.fieldSeasonId)?.fieldId
    return { s, field: fields.find((f) => f.id === fieldId)! }
  }).filter((r) => r.field).sort((a, b) => b.s.flownAt - a.s.flownAt), [surveys, seasons, fields])

  const live = rows.filter((r) => r.s.status === 'PROCESSING' || r.s.status === 'QUEUED')
  const ready = rows.filter((r) => r.s.status === 'READY')
  const next = rows.filter((r) => r.s.status === 'SCHEDULED').sort((a, b) => a.s.flownAt - b.s.flownAt)[0]

  return (
    <>
      <PageHead title="Flights" sub="Every survey flight, from upload to a processed weed map. Photogrammetry and segmentation run on the station server; the phones only receive the result.">
        {decider && <Button variant="primary" icon={Upload} onClick={() => setOpen(true)}>Upload a flight</Button>}
      </PageHead>

      <div className="kpis kpis--4">
        <Card pad><Stat value={ready.length} label="Flights processed this season" /></Card>
        <Card pad><Stat value={ready.reduce((n, r) => n + r.s.images, 0)} format={fmt.int} label="Images captured" /></Card>
        <Card pad><Stat value={live.length} label="Processing now" tone={live.length ? 'wheat' : undefined} /></Card>
        <Card pad><Stat value={next ? fmt.weekdayShort(next.s.flownAt) : '—'} label={next ? `Next: ${next.field.name} · ${fmt.inDays(next.s.flownAt)}` : 'Nothing scheduled'} /></Card>
      </div>

      {live.length > 0 && (
        <>
          <SectionHead title="Processing now" sub="Live: the pipeline advances while this page is open" />
          <div className="col gap-16">
            {live.map(({ s, field }) => <LiveJob key={s.id} s={s} name={field.name} />)}
          </div>
        </>
      )}

      <SectionHead title="All flights" sub={`${rows.length} across ${fields.length} fields`} />
      <Card flush>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Field</th><th>Survey</th><th>Flown</th><th className="num">Images</th><th className="num">GSD</th><th>Sensor</th><th>Status</th><th /></tr></thead>
            <tbody>
              {rows.map(({ s, field }, i) => (
                <motion.tr key={s.id} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.03 }}>
                  <td><div className="row gap-12"><FieldThumb field={field} surveyed width={64} height={46} radius={10} /><div><div className="title-s">{field.name}</div><div className="mono">{s.id}</div></div></div></td>
                  <td>{SURVEY_ROLE[s.role].label}</td>
                  <td>{s.status === 'SCHEDULED' ? <span className="ink-2">Planned {fmt.weekdayShort(s.flownAt)}</span> : <><div>{fmt.date(s.flownAt)}</div><div className="caption">{fmt.time(s.flownAt)} · {s.altitudeM} m AGL</div></>}</td>
                  <td className="num">{s.images ? fmt.int(s.images) : '—'}</td>
                  <td className="num">{s.gsdCm ? `${s.gsdCm} cm/px` : '—'}</td>
                  <td className="ink-2" style={{ fontSize: 13 }}>{s.sensor.replace(' · 20 MP RGB', '')}</td>
                  <td><StatusPill s={s} /></td>
                  <td className="num">{s.status === 'READY' && s.role === 'PRE' ? <Link to={`/app/weed-map/${field.id}`} className="link-btn">Weed map <ArrowRight size={13} /></Link> : s.status === 'READY' ? <Link to={`/app/verification/${field.id}`} className="link-btn">Verify <ArrowRight size={13} /></Link> : null}</td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      <p className="caption" style={{ marginTop: 14 }}>Processed with OpenDroneMap (NodeODM) and {modelAerial}. Flights are planned at 15 m AGL, nadir, 75% front and 70% side overlap.</p>

      {decider && <UploadModal open={open} onClose={close} />}
    </>
  )
}

function StatusPill({ s }: { s: Survey }) {
  if (s.status === 'READY') return <Pill tone="forest" icon={Check}>Ready</Pill>
  if (s.status === 'PROCESSING') return <Pill tone="slate" dot>{s.stage ?? 'Processing'} · {Math.round(s.progress * 100)}%</Pill>
  if (s.status === 'QUEUED') return <Pill tone="slate" dot>Queued</Pill>
  if (s.status === 'FAILED') return <span title={s.error}><Pill tone="clay">Failed</Pill></span>
  return <Pill tone="neutral">{SURVEY_STATUS[s.status]}</Pill>
}

function LiveJob({ s, name }: { s: Survey; name: string }) {
  const idx = stageIndex(s)
  // remaining time from the rate this page has watched the job advance
  const seen = useRef<{ p: number; t: number } | null>(null)
  if (!seen.current || s.progress < seen.current.p) seen.current = { p: s.progress, t: Date.now() }
  const rate = (s.progress - seen.current.p) / Math.max(1, Date.now() - seen.current.t)
  const etaMin = rate > 0 ? Math.max(1, Math.round((1 - s.progress) / rate / 60000)) : null
  return (
    <motion.div layout initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }}>
      <Card pad>
        <div className="job">
          <Ring value={s.progress} size={84} stroke={8} color="var(--slate)">
            <div className="col" style={{ alignItems: 'center' }}><span className="num-m">{Math.round(s.progress * 100)}</span><span className="caption" style={{ fontSize: 10 }}>percent</span></div>
          </Ring>
          <div className="grow">
            <div className="row gap-8"><span className="title-m">{name}</span><Pill tone="slate">{SURVEY_ROLE[s.role].label}</Pill><span className="mono">{s.id}</span></div>
            <div className="body-s" style={{ marginTop: 2 }}>{s.images} images{s.source ? ` · ${s.source}` : ''} · {s.status === 'QUEUED' ? 'waiting for a worker' : etaMin ? `about ${etaMin} min left` : s.stage ?? 'starting'}</div>
            <div className="steps">
              {STAGES.map((st, i) => (
                <div key={st} className={cx('steps__item', i < idx && 'is-done', i === idx && 'is-now')}>
                  <span className="steps__dot">{i < idx ? <Check size={13} /> : i === idx ? <Cpu size={13} /> : i + 1}</span>
                  <span>{st}</span>
                  {i < STAGES.length - 1 && <span className="steps__line"><i style={{ width: i < idx ? '100%' : i === idx ? `${Math.max(0, Math.min(100, ((s.progress - STAGE_FROM[i]) / (STAGE_TO[i] - STAGE_FROM[i])) * 100))}%` : '0%' }} /></span>}
                </div>
              ))}
            </div>
          </div>
        </div>
      </Card>
    </motion.div>
  )
}

/* ------------------------------------------------------------------ upload */

function UploadModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const fields = useStore((s) => s.fields)
  const upload = useStore((s) => s.uploadFlight)
  const toast = useStore((s) => s.toast)
  // default to the first parcel still waiting for its pre-treatment survey
  const firstUnsurveyed = useStore((s) => s.fields.find((f) => !hasSurvey(s, f.id))?.id)
  const [fieldId, setFieldId] = useState(firstUnsurveyed ?? fields[0]?.id ?? '')
  const [role, setRole] = useState<SurveyRole>('PRE')
  const [files, setFiles] = useState<{ count: number; name: string; mb: number; list: File[] | null } | null>(null)
  const [over, setOver] = useState(false)
  const [sending, setSending] = useState<number | null>(null)
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => { if (open) { setFiles(null); setOver(false); setSending(null) } }, [open])

  const take = (list: FileList | File[]) => {
    const arr = [...list]
    if (!arr.length) return
    const mb = arr.reduce((n, f) => n + f.size, 0) / 1e6
    setFiles({ count: arr.length, name: arr.length === 1 ? arr[0].name : `${arr.length} files`, mb, list: arr })
  }
  const field = fields.find((f) => f.id === fieldId) ?? fields[0]
  const start = async () => {
    if (!files) return
    setSending(0)
    try {
      await upload({ fieldId, role, files: files.list, simulateImages: files.list ? undefined : files.count, name: files.name, onProgress: setSending })
      toast(`${field.name}: upload finished. Processing appears under Processing now.`)
      onClose()
    } catch (e) {
      toast(`Upload failed: ${errorMessage(e)}`, { tone: 'warn' })
      setSending(null)
    }
  }

  return (
    <Modal open={open} onClose={onClose} wide title="Upload a flight" sub="Drop the images from the SD card. Processing runs on the station server and continues if you leave this page."
      footer={<>
        <Button variant="ghost" onClick={onClose} disabled={sending !== null}>Cancel</Button>
        <Button variant="primary" icon={PlaneTakeoff} disabled={!files} loading={sending !== null} onClick={() => void start()}>Upload and process</Button>
      </>}>
      <div className="col gap-20" style={{ paddingBottom: 8 }}>
        <div className="row gap-16 wrap">
          <div className="grow" style={{ minWidth: 220 }}>
            <Field label="Field">
              <SelectInput value={fieldId} onChange={(e) => setFieldId(e.target.value)}>
                {fields.map((f) => <option key={f.id} value={f.id}>{f.name} · {f.id}</option>)}
              </SelectInput>
            </Field>
          </div>
          <div className="grow" style={{ minWidth: 300 }}>
            <Field label="Survey role" hint="The role, not the timestamp, decides which survey a follow-up is compared against.">
              <Segmented block value={role} onChange={setRole} options={[{ value: 'PRE', label: 'Pre-treatment' }, { value: 'PLUS_14D', label: '+14 d' }, { value: 'PLUS_28D', label: '+28 d' }]} />
            </Field>
          </div>
        </div>

        <div className={cx('drop', over && 'drop--over', files && 'drop--ok')} role="button" tabIndex={0}
          onClick={() => input.current?.click()} onKeyDown={(e) => e.key === 'Enter' && input.current?.click()}
          onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
          onDrop={(e) => { e.preventDefault(); setOver(false); take(e.dataTransfer.files) }}>
          {files ? (
            <>
              <div className="icon-tile"><Check /></div>
              <div className="title-s">{files.name}</div>
              <div className="body-s">{files.count > 1 ? `${files.count} files` : '1 file'} · {files.mb >= 1000 ? (files.mb / 1000).toFixed(1) + ' GB' : Math.round(files.mb) + ' MB'} {sending !== null && files.list ? `· sending ${Math.round(sending * 100)}%` : 'ready'}</div>
              {sending !== null && files.list && <div style={{ width: 260, marginTop: 10 }}><Meter value={sending} tone="slate" /></div>}
            </>
          ) : (
            <>
              <div className="icon-tile"><FileImage /></div>
              <div className="title-s">Drop the flight images here</div>
              <div className="body-s">JPEG or DNG from the drone, or a zipped folder</div>
            </>
          )}
          <input ref={input} type="file" multiple accept="image/jpeg,image/png,image/tiff,.jpg,.jpeg,.png,.tif,.tiff,.dng,.zip" hidden onChange={(e) => e.target.files && take(e.target.files)} />
        </div>
        {!files && (
          <div className="row gap-12">
            <Button size="sm" variant="tonal" onClick={() => setFiles({ count: role === 'PRE' ? 287 : 281, name: `${field.name.toLowerCase().replace(/\s+/g, '-')}-${role.toLowerCase()}-DJI_0001…${role === 'PRE' ? 287 : 281}.JPG`, mb: 2140, list: null })}>Use a sample flight</Button>
            <span className="caption">The station simulates a {role === 'PRE' ? 287 : 281}-image flight so the demo needs no files (development servers only).</span>
          </div>
        )}
        <div className="fl-note"><Layers size={16} /><span>Zones for <b>{field.name}</b> are regenerated when processing finishes, and published to phones on their next sync.</span></div>
      </div>
    </Modal>
  )
}
