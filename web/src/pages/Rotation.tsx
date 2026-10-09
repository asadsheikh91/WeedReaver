import { motion } from 'framer-motion'
import { AlertTriangle, ArrowRight, Check, ShieldCheck, TrendingDown } from 'lucide-react'
import { useMemo } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { fmt } from '../data/format'
import { useRotation } from '../data/queries'
import { useStore } from '../data/store'
import { hracCode, hracDisplay, HRAC_MOA, WEED_CLASS, type HracGroup, type TreatmentRecord } from '../data/types'
import { TrendChart } from '../ui/charts'
import { Card, Empty, HracBadge, InfoButton, Notice, Pill, SectionHead, Stat, cx } from '../ui/kit'
import { FieldSwitcher, PageHead, useFieldData } from './common'
import './rotation.css'

const ORDINAL = ['First', 'Second', 'Third', 'Fourth', 'Fifth', 'Sixth', 'Seventh', 'Eighth']

export default function Rotation() {
  const { fieldId } = useParams()
  const nav = useNavigate()
  const { field, zones } = useFieldData(fieldId)
  const treatments = useStore((s) => s.treatments)
  const rotation = useRotation(field.id).data
  const rot = rotation?.fieldId === field.id ? rotation : undefined

  const byId = useMemo(() => new Map(treatments.map((t) => [t.id, t])), [treatments])
  // seasons with recorded applications, oldest first, as the server groups them
  const seasons = useMemo(() => (rot?.seasons ?? [])
    .map((x) => [x.season, x.treatments.map((id) => byId.get(id)).filter((t): t is TreatmentRecord => !!t).sort((a, b) => a.appliedAt - b.appliedAt)] as const)
    .filter(([, ts]) => ts.length > 0), [rot, byId])
  const history = seasons.flatMap(([, ts]) => ts)
  const latest = (rot?.latestGroup ?? undefined) as HracGroup | undefined
  const streak = rot?.streak ?? 0
  const control = rot?.trend ?? []
  const last = control[control.length - 1]?.controlPct
  const acceptable = rot?.acceptableControlPct ?? 70
  const risk = rot?.risk ?? 'Low'
  const riskTone = risk === 'High' ? 'clay' : risk === 'Watch' ? 'wheat' : 'moss'

  const classes = new Set(zones.map((z) => z.dominantClass))
  const alts = rot?.alternatives ?? []
  const usedGroups = new Set(rot?.usedGroups ?? [])
  const nextSeason = rot?.nextSeason ?? ''

  return (
    <>
      <PageHead title="Rotation & resistance" sub="Which mode of action has gone onto each field, season by season, and what it has cost in control. This is the output that would have caught the isoproturon failure, then the clodinafop failure, before either became regional.">
        <FieldSwitcher value={field.id} onChange={(id) => nav(`/app/rotation/${id}`)} />
      </PageHead>

      <div className="rot__hero">
        <Card pad>
          <div className="row between start">
            <div>
              <div className="row gap-4"><h3 className="title-m">Control with {latest ? `Group ${hracCode(latest)}` : 'the last mode of action'}</h3><InfoButton title="How control is measured" body={<><p>Control is the drop in weed cover in each zone between the pre-treatment flight and the follow-up flight, taken on the worst zone each season.</p><p>The dashed line marks the level below which the agronomist treats a population as suspect. It is a working reference, not a label claim.</p></>} /></div>
              <div className="caption">Worst zone per season, from the follow-up flight</div>
            </div>
            <Pill tone={riskTone} lg dot>Resistance risk: {risk}</Pill>
          </div>
          {control.length >= 2 ? (
            <div style={{ marginTop: 8 }}><TrendChart values={control.map((c) => c.controlPct)} labels={control.map((c) => `Rabi ${c.season}`)} /></div>
          ) : (
            <Empty icon={TrendingDown} title="Not enough seasons to trend">{history.length ? `${field.name} has ${fmt.plural(seasons.length, 'season')} of recorded applications. A trend needs three follow-up flights.` : 'No applications are recorded on this field yet.'}</Empty>
          )}
        </Card>
        <div className="col gap-16">
          <Card pad>
            <div className="row gap-24">
              <Stat value={streak} label={latest ? `Seasons in a row on Group ${hracCode(latest)}` : 'Seasons on one group'} tone={streak >= 3 ? 'clay' : streak >= 2 ? 'wheat' : undefined} />
              <Stat value={last ?? 0} format={(n) => (last ? `${Math.round(n)}%` : '—')} label="Latest control" tone={last && last < acceptable ? 'clay' : undefined} />
            </div>
          </Card>
          <Card pad style={{ flex: 1 }}>
            <div className="overline">Why {risk.toLowerCase()}</div>
            <ul className="why">
              <li className={cx(streak >= 3 && 'bad', streak === 2 && 'warn')}><Check size={15} />{latest ? `Group ${hracCode(latest)} applied in ${fmt.plural(streak, 'consecutive season')}` : 'No group repeated'}</li>
              <li className={cx(!!last && last < acceptable && 'bad')}><Check size={15} />{last ? `Control fell ${control[0].controlPct}% → ${last}% over ${control.length} seasons` : 'No control trend recorded'}</li>
              <li className={cx(usedGroups.size <= 1 && history.length > 1 && 'warn')}><Check size={15} />{usedGroups.size} mode{usedGroups.size === 1 ? '' : 's'} of action used in {fmt.plural(seasons.length, 'season')}</li>
            </ul>
          </Card>
        </div>
      </div>

      {streak >= 2 && latest && (
        <div style={{ marginTop: 24 }}>
          <Notice tone={streak >= 3 ? 'clay' : 'wheat'} icon={AlertTriangle} title={`${ORDINAL[streak] ?? `${streak + 1}th`} season on Group ${hracCode(latest)} would repeat the selection pressure`}>
            {rot?.warning ?? `${field.name} has had ${HRAC_MOA[latest].toLowerCase()} chemistry every season since ${seasons[Math.max(0, seasons.length - streak)]?.[0] ?? ''}.`} The phones already warn the operator at recording time.
          </Notice>
        </div>
      )}

      <SectionHead title="Season by season" sub={`${field.name} · applications grouped by Rabi season`} />
      <div className="matrix">
        {[...seasons.map(([k, ts]) => ({ k, ts, planned: false })), { k: nextSeason, ts: [], planned: true }].map((row, i, arr) => {
          const prevG = i > 0 ? arr[i - 1].ts[0]?.hracGroup : undefined
          const g = row.ts[0]?.hracGroup
          const repeat = !!g && g === prevG
          return (
            <motion.div key={row.k} className={cx('matrix__row', row.planned && 'is-planned')} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.07 }}>
              <div className="matrix__season"><span className="title-s">Rabi {row.k}</span>{i === arr.length - 1 && <span className="caption">Current</span>}</div>
              {g ? (
                <>
                  <HracBadge g={g} warn={repeat || (streak >= 3 && g === latest)} />
                  <div className="grow"><div className="title-s">{row.ts.map((t) => t.product).filter((v, j, a) => a.indexOf(v) === j).join(', ')}</div><div className="caption">{row.ts[0].activeIngredient} · {fmt.date(row.ts[0].appliedAt)} · {row.ts.map((t) => t.zoneLabels.join(', ')).join('; ')}</div></div>
                  {repeat ? <Pill tone="clay" icon={ArrowRight}>Same group again</Pill> : <Pill tone="neutral">{HRAC_MOA[g]}</Pill>}
                </>
              ) : (
                <div className="grow ink-3">{row.planned ? 'Nothing recorded yet. Choose a group that has not been used here recently.' : 'No application'}</div>
              )}
            </motion.div>
          )
        })}
        {seasons.length === 0 && <Empty icon={ShieldCheck} title="No applications recorded">History appears as operators record treatments.</Empty>}
      </div>

      <SectionHead title="Registered alternatives" sub={`Wheat, ${[...classes].map((c) => WEED_CLASS[c].short.toLowerCase()).join(' and ') || 'any weed class'} · different mode of action from ${latest ? `Group ${hracCode(latest)}` : 'the last used'}`}>
        <span className="caption row gap-6"><ShieldCheck size={14} /> Advisory only. Rates come from the product label.</span>
      </SectionHead>
      <Card flush>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Product</th><th>Active ingredient</th><th>Mode of action</th><th>Target</th><th>Used here before</th></tr></thead>
            <tbody>
              {alts.map((p) => (
                <tr key={p.id}>
                  <td><div className="row gap-12"><HracBadge g={p.hrac} sm /><span className="title-s">{p.trade}</span></div></td>
                  <td>{p.active}</td>
                  <td>{hracDisplay(p.hrac).split(' · ')[1]}</td>
                  <td>{WEED_CLASS[p.target].short}</td>
                  <td>{usedGroups.has(p.hrac) ? <Pill tone="wheat">Group {hracCode(p.hrac)} in {seasons.filter(([, ts]) => ts.some((t) => t.hracGroup === p.hrac)).map(([k]) => k).join(', ')}</Pill> : <Pill tone="moss" icon={Check}>Not used here</Pill>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  )
}
