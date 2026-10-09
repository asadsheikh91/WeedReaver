import { motion } from 'framer-motion'
import {
  ArrowRight, BadgeCheck, CalendarClock, CloudUpload, Cpu, Inbox, MapPin, PlaneTakeoff, RefreshCw, Smartphone, Sprout, TrendingDown,
  type LucideIcon,
} from 'lucide-react'
import { useMemo } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import type { AttentionDto } from '../api/dto'
import { ms, now } from '../data/clock'
import { fieldAcres } from '../data/geo'
import { fmt } from '../data/format'
import { useOverview, useSeasonCalendar } from '../data/queries'
import { isDecider, useStore } from '../data/store'
import { Avatar, Button, Card, Empty, IconTile, Meter, Pill, SectionHead, SeverityPill, Stat, type Tone } from '../ui/kit'
import { FieldThumb, PageHead, usePortfolio } from './common'
import './overview.css'

function greeting() {
  const h = new Date(now()).getHours()
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

interface Attention { id: string; tone: Tone; icon: LucideIcon; title: string; body: string; to: string; cta: string; weight: number }

/** How each kind of item the server ranks is presented. */
const KIND: Record<string, { icon: LucideIcon; cta: string }> = {
  control_failure: { icon: TrendingDown, cta: 'Investigate' },
  weak_control: { icon: TrendingDown, cta: 'Verification' },
  resistance: { icon: TrendingDown, cta: 'Review resistance' },
  zones_open: { icon: Sprout, cta: 'Open field' },
  review_scans: { icon: Inbox, cta: 'Open queue' },
  review_cells: { icon: MapPin, cta: 'Open queue' },
  verify: { icon: BadgeCheck, cta: 'Verify' },
  device_pending: { icon: Smartphone, cta: 'Open devices' },
  flight_processing: { icon: Cpu, cta: 'Watch' },
  flight_due: { icon: CalendarClock, cta: 'View flights' },
}

const toAttention = (a: AttentionDto, i: number): Attention => ({
  id: `${a.kind}-${a.fieldId ?? ''}-${a.refId ?? i}`, tone: a.tone, icon: KIND[a.kind]?.icon ?? Sprout, title: a.title, body: a.detail,
  to: `/app${a.link}`.replace(/\/$/, ''), cta: KIND[a.kind]?.cta ?? 'Open', weight: 100 - a.priority * 10,
})

/** Growth stages of Punjab wheat, by days after sowing. */
const STAGES: [string, number][] = [['Seedling', 28], ['Tillering', 100], ['Stem elongation', 133], ['Heading · grain fill', Infinity]]
const stageAt = (day: number) => STAGES.find(([, end]) => day < end)?.[0] ?? 'Harvest'

export default function Overview() {
  const nav = useNavigate()
  const portfolio = usePortfolio()
  const overview = useOverview().data
  const fieldStatus = useStore((s) => s.fieldStatus)
  const phonePending = useStore((s) => s.phonePending)
  const threshold = useStore((s) => s.threshold)
  const pushed = useStore((s) => s.pushedThreshold)
  const units = useStore((s) => s.units)
  const pull = useStore((s) => s.pullFromPhone)
  const syncing = useStore((s) => s.syncing)
  const me = useStore((s) => s.me)
  const station = useStore((s) => s.station)

  const local = units === 'local'
  const stats = overview?.stats
  const totalAcres = stats?.areaAcres ?? portfolio.reduce((s, p) => s + fieldAcres(p.field), 0)
  const allZones = Object.values(fieldStatus).reduce((n, f) => n + f.zonesTotal, 0)
  const openCount = stats?.zonesOpen ?? 0
  const weakest = stats?.worstControl ?? null
  const unresolved = stats?.scansAwaitingReview ?? 0
  const day = stats?.seasonDay ?? 0
  const total = stats?.seasonLengthDays ?? 1
  const season = stats?.season ?? station?.season ?? ''

  const items = useMemo<Attention[]>(() => {
    const out = (overview?.needsAttention ?? []).map(toAttention)
    if (threshold !== pushed && isDecider(me))
      out.push({
        id: 'thr', tone: 'forest', icon: CloudUpload, weight: 85,
        title: `Threshold ${threshold}% is not published`,
        body: `Phones are still using ${pushed}%. Publish it so the next sync brings the new prescription.`,
        to: '/app/settings', cta: 'Review',
      })
    return out.sort((a, b) => b.weight - a.weight)
  }, [overview, threshold, pushed, me])

  const activity = useMemo(() => {
    const web = (overview?.recentAudit ?? []).map((a) => ({ id: `a-${a.id}`, at: ms(a.at), who: a.who, text: a.action, detail: a.detail, kind: a.who === 'System' ? 'system' as const : 'web' as const }))
    const phone = (overview?.recentActivity ?? []).filter((a) => a.source === 'phone').slice(0, 4)
      .map((a) => ({ id: `p-${a.id}`, at: ms(a.at), who: a.actor ?? 'Phone', text: a.title, detail: a.subtitle, kind: 'phone' as const }))
    return [...web, ...phone].sort((a, b) => b.at - a.at).slice(0, 8)
  }, [overview])

  return (
    <>
      <PageHead
        title={`${greeting()}, ${me?.name ?? ''}`}
        sub={`${fmt.weekday(now())} · ${season} · ${fmt.plural(portfolio.length, 'field')} on ${fmt.area(totalAcres, local)}`}
      >
        <Button icon={PlaneTakeoff} onClick={() => nav('/app/flights?upload=1')}>Upload a flight</Button>
        <Button variant="primary" icon={RefreshCw} loading={syncing === 'pulling'} onClick={() => void pull()}>
          {phonePending ? `${phonePending} waiting on a phone` : 'Check for phone changes'}
        </Button>
      </PageHead>

      <div className="kpis">
        {[
          { v: totalAcres, fmt: (n: number) => (local ? n.toFixed(1) : (n * 0.404686).toFixed(2)), unit: local ? 'ac' : 'ha', label: `Tracked across ${portfolio.length} fields`, sub: local ? fmt.areaAlt(totalAcres, true) : undefined },
          { v: openCount, unit: `of ${allZones}`, label: 'Zones still to spray', sub: fmt.sqm(stats?.zonesOpenSqm ?? 0), tone: openCount ? 'wheat' as const : 'forest' as const },
          { v: weakest?.efficacyPct ?? 0, fmt: (n: number) => (weakest ? `${Math.round(n)}%` : '—'), label: weakest ? `Lowest control · Zone ${weakest.letter}, ${weakest.fieldName}` : 'No follow-up processed yet', tone: (weakest && weakest.efficacyPct < 70 ? 'clay' : 'forest') as Tone },
          { v: unresolved, label: 'Scans to review', sub: unresolved ? 'Model abstained' : 'Queue is clear', tone: unresolved ? 'wheat' as const : 'forest' as const },
        ].map((k, i) => (
          <motion.div key={i} className="kpi card" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.05 * i, duration: 0.4, ease: [0.2, 0, 0, 1] }}>
            <Stat value={k.v} format={k.fmt} unit={k.unit} label={k.label} tone={k.tone} />
            {k.sub && <div className="kpi__sub">{k.sub}</div>}
          </motion.div>
        ))}
        <motion.div className="kpi card" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.2, duration: 0.4, ease: [0.2, 0, 0, 1] }}>
          <Stat value={day} unit={`of ~${total} d`} label={`Season progress · ${stageAt(day)}`} />
          <div className="mt-12"><Meter value={day / total} tone="forest" mark={0.36} /></div>
        </motion.div>
      </div>

      <div className="split">
        <div>
          <SectionHead first title="Needs attention" sub="Ranked by how much a delay costs" />
          <Card flush>
            {items.length === 0 && <Empty icon={Sprout} title="All caught up">Every zone is treated, verified and synced.</Empty>}
            {items.map((it, i) => (
              <motion.div key={it.id} layout initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.04 * i }}>
                <Link to={it.to} className="list-row attention">
                  <IconTile icon={it.icon} tone={it.tone} />
                  <div className="grow">
                    <div className="title-s">{it.title}</div>
                    <div className="body-s">{it.body}</div>
                  </div>
                  <span className="attention__cta">{it.cta}<ArrowRight size={15} /></span>
                </Link>
              </motion.div>
            ))}
          </Card>
        </div>

        <div>
          <SectionHead first title="Fields" sub={`${season} · wheat`}>
            <Link to="/app/fields" className="link-btn">All fields <ArrowRight size={14} /></Link>
          </SectionHead>
          <Card flush>
            {portfolio.map((p) => {
              const done = p.zones.filter((z) => z.state === 'TREATED' || z.state === 'RESURVEYED').length
              const pressure = p.zones.length ? p.zones.some((z) => z.severity === 'HEAVY' && z.state !== 'TREATED') ? 'HEAVY' as const : p.zones.some((z) => z.state !== 'TREATED') ? 'MODERATE' as const : 'CLEAN' as const : 'CLEAN' as const
              return (
                <Link key={p.field.id} to={`/app/fields/${p.field.id}`} className="list-row field-row">
                  <FieldThumb field={p.field} surveyed={p.surveyed} width={96} height={68} />
                  <div className="grow">
                    <div className="row gap-8"><span className="title-s">{p.field.name}</span><span className="mono">{p.field.id}</span></div>
                    <div className="body-s">{fmt.area(fieldAcres(p.field), local)} · {p.field.village.split(',')[0]}</div>
                    {p.zones.length > 0 && (
                      <div className="row gap-8 mt-8"><div className="grow"><Meter value={done / p.zones.length} /></div><span className="caption">{done}/{p.zones.length}</span></div>
                    )}
                  </div>
                  {!p.surveyed ? <Pill tone="slate">Awaiting flight</Pill> : p.zones.length === 0 ? <Pill tone="moss">Clean</Pill> : <SeverityPill s={pressure} label={pressure === 'HEAVY' ? 'High pressure' : pressure === 'MODERATE' ? 'Spot spray' : 'Treated'} />}
                </Link>
              )
            })}
          </Card>
        </div>
      </div>

      <div className="split">
        <div>
          <SectionHead title="Season timeline" sub="Flights and sowing, by field" />
          <Card pad><Timeline /></Card>
        </div>
        <div>
          <SectionHead title="Recent activity" />
          <Card flush>
            {activity.map((a) => (
              <div key={a.id} className="list-row" style={{ alignItems: 'flex-start', padding: '12px 20px' }}>
                {a.kind === 'phone' ? <IconTile sm icon={Smartphone} tone="wheat" /> : a.kind === 'system' ? <IconTile sm icon={Cpu} tone="slate" /> : <span style={{ marginTop: 2 }}><Avatar name={a.who} size={32} /></span>}
                <div className="grow">
                  <div className="title-s" style={{ fontSize: 13.5 }}>{a.text}</div>
                  <div className="body-s truncate" style={{ maxWidth: 300 }}>{a.detail}</div>
                </div>
                <span className="caption nowrap">{fmt.relative(a.at)}</span>
              </div>
            ))}
          </Card>
        </div>
      </div>
    </>
  )
}


/* ------------------------------------------------------------------ timeline */

function Timeline() {
  const cal = useSeasonCalendar().data
  if (!cal?.startsOn) return <div style={{ height: 200 }} />
  return <TimelineChart cal={cal} />
}

function TimelineChart({ cal }: { cal: NonNullable<ReturnType<typeof useSeasonCalendar>['data']> }) {
  const rows = cal.fields
  const W = 640, rowH = 52, padL = 96, padT = 44, padR = 16
  const start = ms(cal.startsOn!)
  const sd = new Date(start)
  const end = new Date(start + cal.seasonLengthDays * 86400000)
  const t0 = new Date(sd.getFullYear(), sd.getMonth(), 1).getTime()
  const t1 = new Date(end.getFullYear(), end.getMonth() + 1, 0).getTime()
  const x = (t: number) => padL + ((t - t0) / (t1 - t0)) * (W - padL - padR)
  const H = padT + rows.length * rowH + 24
  const months: number[] = []
  for (let d = new Date(t0); d.getTime() < t1; d = new Date(d.getFullYear(), d.getMonth() + 1, 1)) months.push(d.getTime())
  const day = (n: number) => start + n * 86400000
  const stages: [string, number, number, string][] = [
    ['Seedling', start, day(28), '#EBF0E2'],
    ['Tillering', day(28), day(100), '#DCE6D2'],
    ['Stem elongation', day(100), day(133), '#EBF0E2'],
    ['Heading · grain fill', day(133), Math.min(t1, day(cal.seasonLengthDays)), '#DCE6D2'],
  ]
  const today = now()
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Season timeline">
      {stages.map(([n, a, b, c]) => (
        <g key={n}>
          <rect x={x(a)} y={8} width={x(b) - x(a)} height={22} rx={6} fill={c} />
          <text x={(x(a) + x(b)) / 2} y={23} textAnchor="middle" fontSize="10.5" fontWeight="600" fill="var(--forest)">{n}</text>
        </g>
      ))}
      {months.map((m) => (
        <g key={m}>
          <line x1={x(m)} x2={x(m)} y1={padT - 6} y2={H - 20} stroke="var(--line)" />
          <text x={x(m) + 5} y={H - 6} fontSize="10.5" fill="var(--ink-3)">{fmt.dayMonth(m).split(' ')[1]}</text>
        </g>
      ))}
      {rows.map((f, r) => {
        const cy = padT + r * rowH + rowH / 2
        const sown = ms(f.season?.sowingDate) ?? start
        return (
          <g key={f.fieldId}>
            <text x={0} y={cy + 4} fontSize="12.5" fontWeight="600" fill="var(--ink)">{f.fieldName}</text>
            <line x1={x(sown)} x2={x(t1)} y1={cy} y2={cy} stroke="var(--line-strong)" strokeWidth="2" strokeDasharray="1 5" strokeLinecap="round" />
            <circle cx={x(sown)} cy={cy} r="5" fill="var(--forest)" />
            {f.surveys.map((s) => {
              const flown = ms(s.flownAt)
              const px = x(flown)
              const done = s.status === 'READY', proc = s.status === 'PROCESSING' || s.status === 'QUEUED'
              return (
                <g key={s.id}>
                  {proc && <circle cx={px} cy={cy} r="13" fill="var(--slate)" opacity=".18"><animate attributeName="r" values="9;18;9" dur="2s" repeatCount="indefinite" /><animate attributeName="opacity" values=".3;0;.3" dur="2s" repeatCount="indefinite" /></circle>}
                  <circle cx={px} cy={cy} r="9" fill={done ? 'var(--forest-2)' : 'var(--ivory)'} stroke={done ? 'var(--forest-2)' : proc ? 'var(--slate)' : 'var(--line-strong)'} strokeWidth="2" />
                  <text x={px} y={cy + 3.4} textAnchor="middle" fontSize="8.5" fontWeight="700" fill={done ? '#fff' : 'var(--ink-2)'}>{s.role === 'PRE' ? 'P' : s.role === 'PLUS_14D' ? '14' : '28'}</text>
                  <title>{`${f.fieldName} · ${s.role === 'PRE' ? 'Pre-treatment' : s.role === 'PLUS_14D' ? '+14 d' : '+28 d'} · ${fmt.dayMonth(flown)} · ${s.status.toLowerCase()}`}</title>
                </g>
              )
            })}
          </g>
        )
      })}
      <line x1={x(today)} x2={x(today)} y1={padT - 12} y2={H - 20} stroke="var(--clay)" strokeWidth="1.6" />
      <rect x={x(today) - 22} y={padT - 26} width="44" height="16" rx="8" fill="var(--clay)" />
      <text x={x(today)} y={padT - 14.5} textAnchor="middle" fontSize="9.5" fontWeight="700" fill="#fff">Today</text>
    </svg>
  )
}

