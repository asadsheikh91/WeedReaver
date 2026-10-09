import { motion } from 'framer-motion'
import { Check, CloudUpload, Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { fmt } from '../data/format'
import { useStore } from '../data/store'
import { hracCode, hracDisplay, type HracGroup, type TreatmentRecord } from '../data/types'
import { Button, Card, Chips, Drawer, Empty, HracBadge, Notice, Pill, SelectInput, Stat, TextInput } from '../ui/kit'
import { StackBar } from '../ui/charts'
import { PageHead } from './common'
import { Droplets } from 'lucide-react'

const GROUP_COLORS: Record<HracGroup, string> = { G1: '#B9552F', G2: '#C9983D', G3: '#3D697D', G4: '#8B6FB0', G5: '#4F8B5C', G9: '#8B9387', G15: '#2D6547' }

export default function Treatments() {
  const treatments = useStore((s) => s.treatments)
  const fields = useStore((s) => s.fields)
  const [fieldId, setFieldId] = useState('all')
  const [group, setGroup] = useState<string>('all')
  const [q, setQ] = useState('')
  const [open, setOpen] = useState<TreatmentRecord | null>(null)

  const groups = useMemo(() => [...new Set(treatments.map((t) => t.hracGroup))].sort((a, b) => Number(hracCode(a)) - Number(hracCode(b))), [treatments])
  const shown = treatments
    .filter((t) => fieldId === 'all' || t.fieldId === fieldId)
    .filter((t) => group === 'all' || t.hracGroup === group)
    .filter((t) => !q || `${t.product} ${t.activeIngredient} ${t.fieldName}`.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => b.appliedAt - a.appliedAt)

  const comp = groups.map((g) => ({ label: `Group ${hracCode(g)}`, value: treatments.filter((t) => t.hracGroup === g).length, color: GROUP_COLORS[g] }))
  const g1 = treatments.filter((t) => t.hracGroup === 'G1').length
  // the field where Group 1 has been used most is where its cost shows first
  const g1Field = useMemo(() => {
    const n = new Map<string, { id: string; name: string; count: number }>()
    for (const t of treatments) if (t.hracGroup === 'G1') n.set(t.fieldId, { id: t.fieldId, name: t.fieldName, count: (n.get(t.fieldId)?.count ?? 0) + 1 })
    return [...n.values()].sort((x, y) => y.count - x.count)[0]
  }, [treatments])
  const acres = treatments.reduce((n, t) => n + t.areaAcres, 0)

  return (
    <>
      <PageHead title="Treatments" sub="What was applied, where, and by which mode of action. Operators record the product and the dose from the label; the system never computes or prescribes one.">
        <Pill tone="neutral" icon={CloudUpload} lg>Recorded on phones</Pill>
      </PageHead>

      <div className="split" style={{ gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', marginBottom: 24 }}>
        <Card pad>
          <div className="row gap-24">
            <Stat value={treatments.length} label="Applications on record" />
            <Stat value={acres} format={(n) => n.toFixed(1)} unit="ac" label="Area treated" />
          </div>
        </Card>
        <Card pad>
          <div className="overline" style={{ marginBottom: 10 }}>Mode of action, all applications</div>
          <StackBar parts={comp} />
        </Card>
      </div>
      {g1 >= 3 && (
        <div style={{ marginBottom: 24 }}>
          <Notice tone="clay" icon={Droplets} title={`Group 1 accounts for ${g1} of ${treatments.length} applications`}
            action={<Link to={`/app/rotation/${g1Field?.id ?? ''}`} className="link-btn">Open the rotation history →</Link>}>
            Repeating one mode of action on a surviving population is how resistance is selected. The rotation view shows what it has cost on {g1Field?.name}.
          </Notice>
        </div>
      )}

      <div className="row gap-12 wrap" style={{ marginBottom: 16 }}>
        <SelectInput sm style={{ width: 180 }} value={fieldId} onChange={(e) => setFieldId(e.target.value)} aria-label="Field">
          <option value="all">All fields</option>
          {fields.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </SelectInput>
        <Chips value={group} onChange={setGroup} options={[{ value: 'all', label: 'All groups' }, ...groups.map((g) => ({ value: g, label: `Group ${hracCode(g)}` }))]} />
        <div className="grow" />
        <div className="input-wrap" style={{ width: 260 }}><Search /><TextInput className="input--search" sm placeholder="Search product or ingredient" value={q} onChange={(e) => setQ(e.target.value)} /></div>
      </div>

      <Card flush>
        {shown.length === 0 ? <Empty icon={Droplets} title="No applications match">Clear a filter to see more of the record.</Empty> : (
          <div className="table-wrap">
            <table className="table table--click">
              <thead><tr><th>Date</th><th>Field</th><th>Product</th><th>Group</th><th className="num">Dose recorded</th><th>Zones</th><th className="num">Area</th><th>Operator</th></tr></thead>
              <tbody>
                {shown.map((t, i) => (
                  <motion.tr key={t.id} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.03 }} onClick={() => setOpen(t)}>
                    <td><div className="title-s">{fmt.date(t.appliedAt)}</div><div className="caption">{fmt.time(t.appliedAt)}</div></td>
                    <td className="title-s">{t.fieldName}</td>
                    <td><div className="title-s">{t.product}</div><div className="caption">{t.activeIngredient}</div></td>
                    <td><div className="row gap-8"><HracBadge g={t.hracGroup} sm /><span className="caption">{hracDisplay(t.hracGroup).split(' · ')[1]}</span></div></td>
                    <td className="num">{t.doseRecorded} <span className="ink-3">{t.doseUnit}</span></td>
                    <td>{t.zoneLabels.map((z) => z.replace('Zone ', '')).join(', ')}</td>
                    <td className="num">{t.areaAcres.toFixed(1)} ac</td>
                    <td className="ink-2">{t.operator}</td>
                  </motion.tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Drawer open={!!open} onClose={() => setOpen(null)} title={open?.product ?? ''} sub={open ? `${open.fieldName} · ${fmt.dateTime(open.appliedAt)}` : ''}
        footer={<Button variant="secondary" block onClick={() => setOpen(null)}>Close</Button>}>
        {open && (
          <>
            <div className="row gap-8 wrap" style={{ margin: '4px 0 16px' }}>
              <Pill tone={open.hracGroup === 'G1' ? 'clay' : 'forest'}>{hracDisplay(open.hracGroup)}</Pill>
              <Pill tone={open.synced ? 'forest' : 'wheat'} icon={open.synced ? Check : CloudUpload}>{open.synced ? 'Synced' : 'Waiting to sync'}</Pill>
            </div>
            <Card flush>
              {([
                ['Active ingredient', open.activeIngredient], ['Dose recorded', `${open.doseRecorded} ${open.doseUnit}`],
                ['Spray volume', open.waterLitres ? `${open.waterLitres} L / acre` : '—'], ['Zones', open.zoneLabels.join(', ')],
                ['Area', `${open.areaAcres.toFixed(1)} ac`], ['Method', open.applicationMode], ['Crop stage', open.growthStage],
                ['Operator', open.operator], ['Record', open.id],
              ] as const).map(([k, v]) => (
                <div key={k} className="list-row" style={{ padding: '12px 20px', justifyContent: 'space-between' }}>
                  <span className="body-s">{k}</span><span className="title-s" style={{ textAlign: 'right' }}>{v}</span>
                </div>
              ))}
            </Card>
            <p className="caption" style={{ marginTop: 14 }}>Dose is transcribed from the product label by the operator. This system publishes no application rates.</p>
          </>
        )}
      </Drawer>
    </>
  )
}
