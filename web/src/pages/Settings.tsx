import { motion } from 'framer-motion'
import { Cloud, Copy, KeyRound, Lock, RotateCcw, UserPlus } from 'lucide-react'
import { useState } from 'react'
import { fmt } from '../data/format'
import { api, auth, errorMessage, type TokenOut } from '../api/client'
import { useGridStats } from '../data/queries'
import { hasSurvey, isDecider, useStore } from '../data/store'
import { GRID_ACTUATOR, type GridSize } from '../data/types'
import { Avatar, Button, Card, Field, Hairline, InfoButton, Modal, Pill, Segmented, SelectInput, Slider, Stat, TextInput, type Tone } from '../ui/kit'
import { PageHead } from './common'
import './settings.css'

const SECTIONS = [['prescription', 'Prescription'], ['grid', 'Grid and units'], ['models', 'Models'], ['team', 'Team'], ['audit', 'Audit log'], ['account', 'Account'], ['data', 'Data']] as const

const DASHBOARD_VERSION: string = import.meta.env.VITE_APP_VERSION ?? '0.9.4'
const ROLES = [['ANALYST', 'Analyst'], ['OPERATOR', 'Field operator'], ['TRAINEE', 'Trainee'], ['ADMIN', 'Administrator']] as const

const ROLE_TONE: Record<string, Tone> = { Analyst: 'forest', 'Field operator': 'wheat', Trainee: 'slate', Administrator: 'neutral' }

export default function Settings() {
  const threshold = useStore((s) => s.threshold)
  const pushed = useStore((s) => s.pushedThreshold)
  const setThreshold = useStore((s) => s.setThreshold)
  const push = useStore((s) => s.pushToPhone)
  const syncing = useStore((s) => s.syncing)
  const gridSize = useStore((s) => s.gridSize)
  const saveDefaults = useStore((s) => s.saveStationDefaults)
  const units = useStore((s) => s.units)
  const users = useStore((s) => s.users)
  const audit = useStore((s) => s.audit)
  const reload = useStore((s) => s.reload)
  const sendInvite = useStore((s) => s.invite)
  const toast = useStore((s) => s.toast)
  const station = useStore((s) => s.station)
  const me = useStore((s) => s.me)
  const decider = isDecider(me)
  const admin = me?.role === 'ADMIN'
  // impact is shown on the first surveyed field, the one with a weed surface to measure
  const field = useStore((s) => s.fields.find((f) => hasSurvey(s, f.id)) ?? s.fields[0])
  const [invite, setInvite] = useState(false)
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState('OPERATOR')
  const [link, setLink] = useState<string | null>(null)
  const [inviting, setInviting] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [pw, setPw] = useState(false)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [changing, setChanging] = useState(false)
  const [active, setActive] = useState<string>('prescription')
  const thrMin = station?.thresholdMin ?? 2, thrMax = station?.thresholdMax ?? 40

  const g = useGridStats(field?.id, gridSize, threshold, !!field) ?? { treatedSqm: 0, treatedFraction: 0, abstained: 0 }
  const closeInvite = () => { setInvite(false); setEmail(''); setName(''); setLink(null) }
  const submitInvite = async () => {
    setInviting(true)
    try {
      const inv = await sendInvite({ email: email.trim(), role, name: name.trim() })
      if (inv.link) setLink(inv.link)
      else { toast(`Invitation sent to ${inv.email}`, { tone: 'ok' }); closeInvite() }
    } catch (e) { toast(`Invitation not sent: ${errorMessage(e)}`, { tone: 'warn' }) }
    setInviting(false)
  }
  const closePw = () => { setPw(false); setCurrent(''); setNext('') }
  const submitPw = async () => {
    setChanging(true)
    try {
      // The server signs out every session and hands this one fresh tokens.
      auth.accept(await api.post<TokenOut>('/auth/change-password', { currentPassword: current, newPassword: next }))
      toast('Password changed. Other sessions have been signed out.', { tone: 'ok' })
      closePw()
    } catch (e) { toast(`Password not changed: ${errorMessage(e)}`, { tone: 'warn' }) }
    setChanging(false)
  }
  const go = (id: string) => { setActive(id); document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }

  return (
    <>
      <PageHead title="Settings" sub={`${station?.orgName ?? ''} · ${station?.season ?? ''}`} />
      <div className="st">
        <nav className="st__nav" aria-label="Settings sections">
          {SECTIONS.map(([id, label]) => <button key={id} className={active === id ? 'is-on' : ''} onClick={() => go(id)}>{label}</button>)}
        </nav>
        <div className="st__body">
          <section id="prescription">
            <Card pad>
              <div className="row between start">
                <div>
                  <div className="row gap-4"><h3 className="title-l">Prescription threshold</h3><InfoButton title="Why this lives here" body={<><p>This is the one number that decides how much area is sprayed, and so what the system instructs. Changing what the system instructs belongs on the dashboard.</p><p>The phone changes what the system believes because a person saw something. It shows this value read-only.</p></>} /></div>
                  <p className="body-s" style={{ maxWidth: 520, marginTop: 4 }}>A cell joins the prescription when any part of it holds at least this much weed cover. Set here by the analyst; the field app displays it read-only.</p>
                </div>
                <Pill tone="neutral" icon={Lock}>Phones read-only</Pill>
              </div>
              <div className="st__thr">
                <div className="st__big"><span className="num-xl">{threshold}</span><span className="num-l ink-2">%</span></div>
                <div className="grow"><Slider value={threshold} min={thrMin} max={thrMax} onChange={setThreshold} label="Prescription threshold" /><div className="row between caption"><span>{thrMin}% · more area</span><span>{thrMax}% · dense cores only</span></div></div>
              </div>
              <div className="st__impact">
                <Stat sm value={g.treatedSqm} format={fmt.sqm} label={`To spray on ${field?.name ?? ''}`} />
                <Stat sm value={g.treatedFraction * 100} format={(n) => `${n.toFixed(1)}%`} label="Of the field" />
                <Stat sm value={g.abstained} label="Abstained cells" />
                <div className="grow" />
                <div className="col gap-6" style={{ alignItems: 'flex-end' }}>
                  <Button variant="primary" icon={Cloud} disabled={threshold === pushed || !decider} loading={syncing === 'pushing'} onClick={() => void push()}>{threshold === pushed ? `Published: ${pushed}%` : `Publish ${threshold}% to phones`}</Button>
                  <span className="caption">{threshold === pushed ? (station?.thresholdPublishedAt ? `Published ${fmt.relative(station.thresholdPublishedAt)}${station.thresholdPublishedBy ? ` by ${station.thresholdPublishedBy}` : ''}` : 'Phones are up to date') : `Phones still use ${pushed}%`}</span>
                </div>
              </div>
            </Card>
          </section>

          <section id="grid">
            <Card pad>
              <h3 className="title-l">Grid and units</h3>
              <div className="st__row"><div><div className="title-s">Default spray grid</div><div className="caption">{GRID_ACTUATOR[gridSize]}</div></div><Segmented value={gridSize} onChange={(v: GridSize) => void saveDefaults({ defaultGridM: v })} options={[{ value: 1, label: '1 m' }, { value: 2, label: '2 m' }, { value: 5, label: '5 m' }]} /></div>
              <Hairline />
              <div className="st__row"><div><div className="title-s">Area units</div><div className="caption">Acre, kanal and marla are what a farmer here uses; hectares are shown secondary</div></div><Segmented value={units} onChange={(v: 'local' | 'metric') => void saveDefaults({ defaultUnits: v })} options={[{ value: 'local', label: 'Acre · kanal' }, { value: 'metric', label: 'Hectare' }]} /></div>
            </Card>
          </section>

          <section id="models">
            <Card flush>
              <div style={{ padding: '20px 24px 8px' }}><h3 className="title-l">Models</h3><p className="body-s">Read-only. Updates are published here and downloaded by phones on sync.</p></div>
              {[['Aerial segmentation', station?.modelAerial ?? '—', 'Runs on the station server · 3 classes: crop, grass weed, broadleaf weed', 'Server'], ['Leaf scanner', station?.modelLeaf ?? '—', 'INT8 · runs on the phone · abstains below its calibrated threshold', station?.leafModelSizeMb ? `On phone · ${station.leafModelSizeMb} MB` : 'On phone'], ['Field app', station?.appVersion ?? '—', 'Android · minSdk 24', 'Latest'], ['Dashboard', DASHBOARD_VERSION, 'This application', 'Current']].map(([t, v, d, p], i) => (
                <div key={t}>{i >= 0 && <Hairline />}<div className="list-row"><div className="grow"><div className="title-s">{t}</div><div className="caption">{d}</div></div><span className="mono" style={{ color: 'var(--ink)' }}>{v}</span><Pill tone="neutral">{p}</Pill></div></div>
              ))}
            </Card>
          </section>

          <section id="team">
            <Card flush>
              <div className="row between" style={{ padding: '20px 24px 12px' }}><div><h3 className="title-l">Team</h3><p className="body-s">Who can sign in to the dashboard and the field app.</p></div>{admin && <Button icon={UserPlus} onClick={() => setInvite(true)}>Invite</Button>}</div>
              {users.map((u) => (
                <div key={u.id}><Hairline /><div className="list-row"><Avatar name={u.name} size={40} /><div className="grow"><div className="title-s">{u.name}</div><div className="caption">{u.email} · {u.scope}</div></div><Pill tone={ROLE_TONE[u.role] ?? 'neutral'}>{u.role}</Pill><span className="caption" style={{ width: 96, textAlign: 'right' }}>{u.lastActive ? fmt.relative(u.lastActive) : 'Never'}</span></div></div>
              ))}
            </Card>
          </section>

          <section id="audit">
            <Card flush>
              <div style={{ padding: '20px 24px 12px' }}><h3 className="title-l">Audit log</h3><p className="body-s">Every change to a definition is recorded with who made it.</p></div>
              {audit.slice(0, 10).map((a, i) => (
                <motion.div key={a.id} initial={i === 0 ? { opacity: 0, backgroundColor: 'rgba(220,230,210,1)' } : false} animate={{ opacity: 1, backgroundColor: 'rgba(220,230,210,0)' }} transition={{ duration: 1.4 }}>
                  <Hairline /><div className="list-row"><div className="grow"><div className="title-s">{a.action}</div><div className="caption">{a.detail}</div></div><span className="body-s">{a.who}</span><span className="caption" style={{ width: 110, textAlign: 'right' }}>{fmt.relative(a.at)}</span></div>
                </motion.div>
              ))}
            </Card>
          </section>

          <section id="account">
            <Card pad>
              <div className="row between"><div><h3 className="title-l">Account</h3><p className="body-s">{me ? `Signed in as ${me.email}.` : ''} Changing your password signs out every other session, including phones.</p></div><Button variant="secondary" icon={KeyRound} onClick={() => setPw(true)}>Change password</Button></div>
            </Card>
          </section>

          <section id="data">
            <Card pad>
              <div className="row between"><div><h3 className="title-l">Data</h3><p className="body-s" style={{ maxWidth: 520 }}>Everything on this dashboard lives on the station server and is shared with the phones. Reload to pick up changes made elsewhere. To restore the demonstration data, run <code className="mono">python -m app.cli seed-demo</code> on the server.</p></div><Button variant="secondary" icon={RotateCcw} loading={reloading} onClick={() => { setReloading(true); void reload().then(() => toast('Data reloaded from the station server'), (e) => toast(errorMessage(e), { tone: 'warn' })).finally(() => setReloading(false)) }}>Reload</Button></div>
            </Card>
          </section>
        </div>
      </div>

      <Modal open={invite} onClose={closeInvite} title="Invite a teammate" sub={link ? 'No mail server is configured, so send them this link yourself. It works once.' : 'They receive a sign-in link by email.'}
        footer={link
          ? <><Button variant="ghost" onClick={closeInvite}>Done</Button><Button variant="primary" icon={Copy} onClick={() => { void navigator.clipboard?.writeText(link); toast('Invitation link copied', { tone: 'ok' }) }}>Copy link</Button></>
          : <><Button variant="ghost" onClick={closeInvite}>Cancel</Button><Button variant="primary" loading={inviting} disabled={!email.includes('@')} onClick={() => void submitInvite()}>Send invitation</Button></>}>
        {link ? (
          <div className="col gap-16" style={{ paddingBottom: 8 }}>
            <Field label="Invitation link"><TextInput readOnly value={link} onFocus={(e) => e.target.select()} /></Field>
          </div>
        ) : (
          <div className="col gap-16" style={{ paddingBottom: 8 }}>
            <Field label="Email"><TextInput type="email" placeholder="name@pindibhattian-station.pk" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label="Name" optional><TextInput value={name} onChange={(e) => setName(e.target.value)} /></Field>
            <Field label="Role"><SelectInput value={role} onChange={(e) => setRole(e.target.value)}>{ROLES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</SelectInput></Field>
          </div>
        )}
      </Modal>

      <Modal open={pw} onClose={closePw} title="Change password" sub="Every other session, including phones, will need to sign in again."
        footer={<><Button variant="ghost" onClick={closePw}>Cancel</Button><Button variant="primary" loading={changing} disabled={!current || next.length < 8} onClick={() => void submitPw()}>Change password</Button></>}>
        <div className="col gap-16" style={{ paddingBottom: 8 }}>
          <Field label="Current password"><TextInput type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} /></Field>
          <Field label="New password" hint="At least 8 characters."><TextInput type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} /></Field>
        </div>
      </Modal>
    </>
  )
}
