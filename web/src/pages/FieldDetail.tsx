import { ArrowLeft, BadgeCheck, Check, Droplets, FileDown, Layers, PlaneTakeoff, Route, Sprout } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { fmt } from '../data/format'
import { fieldAcres, fieldPerimeter, kanalMarla } from '../data/geo'
import { now } from '../data/clock'
import { hasSurvey, seasonOf, surveysOf, useStore } from '../data/store'
import { SURVEY_ROLE, SURVEY_STATUS, WEED_CLASS, hracDisplay } from '../data/types'
import { LeafImage } from '../map/leaf'
import { MapView, type MapHandle } from '../map/MapView'
import { dimOutside, fieldOutline, heatLayer, pinHit, zonePin, type DrawCtx } from '../map/overlays'
import { Button, Card, Empty, HracBadge, Pill, SectionHead, SeverityPill, Stat, ZoneBadge, cx } from '../ui/kit'
import { PageHead, useFieldData } from './common'
import './fields.css'

export function FieldStatusPill({ fieldId }: { fieldId: string }) {
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const published = useStore((s) => s.zones)
  const fields = useStore((s) => s.fields)
  const state = { seasons, surveys }
  const f = fields.find((x) => x.id === fieldId)
  if (!f) return null
  if (!hasSurvey(state, fieldId)) {
    const next = surveysOf(state, fieldId).find((s) => s.status === 'SCHEDULED' || s.status === 'PROCESSING' || s.status === 'QUEUED')
    return <Pill tone="slate">{next ? (next.status === 'SCHEDULED' ? `Flight ${fmt.dayMonth(next.flownAt)}` : SURVEY_STATUS[next.status]) : 'No survey'}</Pill>
  }
  const zones = published[fieldId] ?? []
  if (!zones.length) return <Pill tone="moss">Clean</Pill>
  const left = zones.filter((z) => z.state !== 'TREATED' && z.state !== 'RESURVEYED')
  if (!left.length) return <Pill tone="forest" icon={Check}>Treated</Pill>
  if (left.some((z) => z.severity === 'HEAVY')) return <Pill tone="clay" dot>High pressure</Pill>
  return <Pill tone="wheat" dot>Spot spray</Pill>
}

export default function FieldDetail() {
  const { fieldId } = useParams()
  const nav = useNavigate()
  const fields = useStore((s) => s.fields)
  if (!fields.some((f) => f.id === fieldId)) return <Navigate to="/app/fields" replace />
  return <Detail id={fieldId!} nav={nav} />
}

function Detail({ id, nav }: { id: string; nav: ReturnType<typeof useNavigate> }) {
  const { field, surveyed, zones, grid, threshold } = useFieldData(id)
  const seasons = useStore((s) => s.seasons)
  const surveys = useStore((s) => s.surveys)
  const scans = useStore((s) => s.scans).filter((x) => x.fieldId === id)
  const treatments = useStore((s) => s.treatments).filter((x) => x.fieldId === id)
  const units = useStore((s) => s.units)
  const map = useRef<MapHandle>(null)
  const [active, setActive] = useState<string | null>(null)
  const local = units === 'local'

  const season = seasonOf({ seasons }, id)
  const list = surveysOf({ seasons, surveys }, id)
  const pre = list.find((s) => s.role === 'PRE')
  const follow = list.find((s) => s.role === 'PLUS_14D')
  const acres = field.areaAcres ?? fieldAcres(field)
  const done = zones.filter((z) => z.state === 'TREATED' || z.state === 'RESURVEYED').length
  const followReady = follow?.status === 'READY'
  const recorded = treatments.some((t) => t.appliedAt > (season?.sowingDate ?? 0))

  const steps: { title: string; detail: string; state: 'done' | 'now' | 'todo'; to?: string }[] = [
    { title: 'Survey', detail: surveyed && pre ? `${fmt.dayMonth(pre.flownAt)} · ${pre.images} images` : pre ? `${SURVEY_STATUS[pre.status]} · ${fmt.weekdayShort(pre.flownAt)}` : 'Not planned', state: surveyed ? 'done' : 'now', to: '/app/flights' },
    { title: 'Route', detail: surveyed ? `${fmt.plural(zones.length, 'zone')} published to phones` : 'After the survey', state: !surveyed ? 'todo' : 'done', to: `/app/weed-map/${id}` },
    { title: 'Treat', detail: zones.length ? `${done} of ${zones.length} zones marked treated` : 'Nothing to treat', state: !surveyed ? 'todo' : zones.length && done === zones.length ? 'done' : 'now' },
    { title: 'Record', detail: recorded ? 'Product and dose recorded' : 'Operator records product and dose', state: recorded ? 'done' : surveyed && done > 0 ? 'now' : 'todo', to: '/app/treatments' },
    { title: 'Verify', detail: followReady ? `+14 d flight ready (${fmt.dayMonth(follow!.flownAt)})` : follow ? `+14 d flight ${SURVEY_STATUS[follow.status].toLowerCase()}` : 'After the follow-up flight', state: followReady ? 'now' : 'todo', to: `/app/verification/${id}` },
  ]

  const draw = (dc: DrawCtx) => {
    dimOutside(dc, field.boundary, 0.22)
    if (grid) heatLayer(dc, grid, field.boundary, 0.92, false)
    fieldOutline(dc, field.boundary)
    zones.forEach((z) => zonePin(dc, z, { size: 28, active: z.id === active }))
  }

  return (
    <>
      <Link to="/app/fields" className="back-link"><ArrowLeft size={15} /> Fields</Link>
      <PageHead
        title={<span className="row gap-12 wrap">{field.name}<span className="mono" style={{ alignSelf: 'center' }}>{field.id}</span><FieldStatusPill fieldId={id} /></span>}
        sub={`${field.village} · ${season?.crop ?? ''} ${season?.variety ?? ''}${season?.sowingDate ? `, sown ${fmt.date(season.sowingDate)}` : ''}`}
      >
        <Button icon={FileDown} onClick={() => nav('/app/exports')}>Export</Button>
        <Button variant="primary" icon={Layers} disabled={!surveyed} onClick={() => nav(`/app/weed-map/${id}`)}>Open weed map</Button>
      </PageHead>

      <div className="fd__hero">
        <div className="fd__map">
          <MapView
            ref={map} field={field} surveyed={surveyed} fitPad={40} animated={!!active} coords
            className="wm__map" draw={draw}
            onClick={(_, s) => { const z = pinHit({ cam: map.current!.camera }, zones, s.x, s.y, 28); setActive(z ? z.id : null) }}
          />
        </div>
        <div className="col gap-16">
          <Card pad>
            <div className="row gap-24">
              <Stat value={local ? acres : acres * 0.404686} format={(n) => n.toFixed(2)} unit={local ? 'ac' : 'ha'} label={local ? `${kanalMarla(acres).kanal} kanal ${kanalMarla(acres).marla} marla` : `${acres.toFixed(2)} ac`} />
              <Stat value={fmt.meters(fieldPerimeter(field))} label="Perimeter" />
            </div>
            <div className="fd__facts mt-16">
              <div className="fact"><span>Days since sowing</span><b>{season?.sowingDate ? `${fmt.daysBetween(season.sowingDate, now())} days` : '—'}</b></div>
              <div className="fact"><span>Row spacing</span><b>{season?.rowSpacingCm ? `${season.rowSpacingCm} cm` : '—'}</b></div>
              <div className="fact"><span>Boundary</span><b>{field.captureMethod}</b></div>
              <div className="fact"><span>Gate</span><b>West bund</b></div>
              <div className="fact"><span>Coordinates</span><b className="mono" style={{ color: 'var(--ink)' }}>{field.lat.toFixed(4)}, {field.lon.toFixed(4)}</b></div>
            </div>
          </Card>
          <Card pad>
            <div className="overline">Zones</div>
            {zones.length ? (
              <div className="col gap-8 mt-12">
                {zones.map((z) => (
                  <button key={z.id} className="row gap-12" style={{ textAlign: 'left', padding: '4px 0', borderRadius: 10, background: active === z.id ? 'var(--sage-tint)' : 'transparent' }}
                    onClick={() => { setActive(z.id); map.current?.flyTo({ x: z.cx, y: z.cy }, 6.5) }}>
                    <ZoneBadge z={z} size={30} />
                    <span className="grow title-s">{z.label}</span>
                    <span className="caption">{fmt.sqm(z.areaSqm)}</span>
                    <SeverityPill s={z.severity} />
                  </button>
                ))}
              </div>
            ) : <p className="body-s mt-8">{surveyed ? 'Nothing crossed the prescription threshold.' : 'Zones appear when the pre-treatment flight is processed.'}</p>}
          </Card>
        </div>
      </div>

      <SectionHead title="Treatment loop" sub="Where this field is between survey and verification" />
      <Card flush>
        <div className="loop">
          {steps.map((s, i) => {
            const Ico = [PlaneTakeoff, Route, Sprout, Droplets, BadgeCheck][i]
            const inner = (
              <>
                <div className="loop__dot">{s.state === 'done' ? <Check /> : i + 1}</div>
                <div className="loop__title row gap-6"><Ico size={15} style={{ color: 'var(--ink-3)' }} />{s.title}</div>
                <div className="loop__detail">{s.detail}</div>
              </>
            )
            return s.to
              ? <Link key={s.title} to={s.to} className="loop__step" data-state={s.state}>{inner}</Link>
              : <div key={s.title} className="loop__step" data-state={s.state}>{inner}</div>
          })}
        </div>
      </Card>

      <SectionHead title={`Zones · ${zones.length}`} sub={`At the ${threshold}% prescription threshold`} />
      <Card flush>
        {zones.length === 0 ? (
          <Empty icon={surveyed ? Check : PlaneTakeoff} title={surveyed ? 'No weed pressure' : 'Waiting for the survey'}>
            {surveyed ? 'Nothing crossed the prescription threshold on this flight.' : 'Zones are produced when the pre-treatment flight has been processed on this dashboard.'}
          </Empty>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Zone</th><th>Class</th><th>Pressure</th><th className="num">Area</th><th className="num">Mean cover</th><th className="num">From previous</th><th>State</th><th style={{ width: 200 }}>Control at +14 d</th></tr></thead>
              <tbody>
                {zones.map((z) => {
                  const pct = z.efficacyPct
                  return (
                    <tr key={z.id} style={{ background: active === z.id ? 'var(--sage-tint)' : undefined }}>
                      <td><div className="row gap-12"><ZoneBadge z={z} /><span className="title-s">{z.label}</span></div></td>
                      <td>{WEED_CLASS[z.dominantClass].label}</td>
                      <td><SeverityPill s={z.severity} /></td>
                      <td className="num">{fmt.sqm(z.areaSqm)}</td>
                      <td className="num">{Math.round(z.meanInfestPct)}%</td>
                      <td className="num">{z.distanceM} m</td>
                      <td>{z.state === 'TREATED' || z.state === 'RESURVEYED' ? <Pill tone="forest" icon={Check}>Treated</Pill> : z.state === 'ROUTED' ? <Pill tone="slate">On the route</Pill> : <Pill tone="neutral">Flagged</Pill>}</td>
                      <td>
                        {followReady && pct != null ? (
                          <div className="row gap-12"><div className="grow"><div className={cx('meter', pct < 70 && 'meter--clay')}><i style={{ width: `${pct}%` }} /></div></div><b className={cx('title-s')} style={{ color: pct < 70 ? 'var(--clay-ink)' : 'var(--forest)', width: 38, textAlign: 'right' }}>{pct}%</b></div>
                        ) : <span className="ink-3">—</span>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="split" style={{ marginTop: 8 }}>
        <div>
          <SectionHead title="Flights" />
          <Card flush>
            {list.map((s, i) => (
              <div key={s.id} className={cx('list-row')} style={i ? undefined : undefined}>
                <div className="icon-tile icon-tile--slate icon-tile--sm"><PlaneTakeoff /></div>
                <div className="grow"><div className="title-s">{SURVEY_ROLE[s.role].label}</div><div className="caption">{fmt.weekdayShort(s.flownAt)} · {s.images ? `${s.images} images` : 'not flown'} · {s.id}</div></div>
                <Pill tone={s.status === 'READY' ? 'forest' : s.status === 'PROCESSING' ? 'slate' : 'neutral'} dot={s.status === 'PROCESSING'}>{s.status === 'PROCESSING' ? `${Math.round(s.progress * 100)}%` : SURVEY_STATUS[s.status]}</Pill>
              </div>
            ))}
          </Card>
        </div>
        <div>
          <SectionHead title="Scans from this field" />
          <Card flush>
            {scans.length === 0 && <Empty icon={Sprout} title="No scans yet">Leaf scans appear here after the operator's phone syncs.</Empty>}
            {scans.map((s) => (
              <Link key={s.id} to="/app/review" className="list-row">
                <div style={{ width: 44, height: 44, borderRadius: 12, overflow: 'hidden', flex: 'none' }}><LeafImage seed={s.leafSeed} w={132} h={176} /></div>
                <div className="grow"><div className="title-s">{s.abstained && !s.resolved ? 'Needs a label' : s.annotation ?? s.speciesLatin}</div><div className="caption">{s.zoneLabel ?? 'Outside zones'} · {fmt.pct(s.confidence)} · {fmt.relative(s.at)}</div></div>
                {s.abstained && !s.resolved ? <Pill tone="wheat">Review</Pill> : <Pill tone="neutral">{s.id}</Pill>}
              </Link>
            ))}
          </Card>
        </div>
      </div>

      {treatments.length > 0 && (
        <>
          <SectionHead title="Applications on this field" />
          <Card flush>
            {treatments.map((t) => (
              <div key={t.id} className="list-row">
                <HracBadge g={t.hracGroup} />
                <div className="grow"><div className="title-s">{t.product}</div><div className="caption">{t.activeIngredient} · {fmt.date(t.appliedAt)} · {fmt.plural(t.zoneLabels.length, 'zone')}</div></div>
                <span className="caption">{hracDisplay(t.hracGroup)}</span>
              </div>
            ))}
          </Card>
        </>
      )}
    </>
  )
}

