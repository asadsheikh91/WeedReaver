import { motion } from 'framer-motion'
import { BatteryMedium, BatteryLow, Ban, Check, Clock, HardDrive, RefreshCw, RotateCcw, Smartphone, Wifi, WifiOff, X } from 'lucide-react'
import { fmt } from '../data/format'
import { now } from '../data/clock'
import { useStore } from '../data/store'
import type { Device } from '../data/types'
import { MapView } from '../map/MapView'
import { dimOutside, fieldOutline, locationPuck, type DrawCtx } from '../map/overlays'
import { Button, Card, Meter, Pill, SectionHead, cx } from '../ui/kit'
import { PageHead } from './common'
import './devices.css'

export default function Devices() {
  const devices = useStore((s) => s.devices)
  const changeLog = useStore((s) => s.changeLog)
  const pending = useStore((s) => s.phonePending)
  const syncing = useStore((s) => s.syncing)
  const progress = useStore((s) => s.syncProgress)
  const revoke = useStore((s) => s.revokeDevice)
  const pull = useStore((s) => s.pullFromPhone)
  const admin = useStore((s) => s.me?.role === 'ADMIN')
  const field = useStore((s) => s.fields[0])
  // the handset heard from most recently
  const d1 = [...devices].sort((a, b) => (b.lastSeen ?? 0) - (a.lastSeen ?? 0))[0]

  const draw = (dc: DrawCtx) => {
    if (!field) return
    dimOutside(dc, field.boundary, 0.28)
    fieldOutline(dc, field.boundary, { width: 1.8 })
    if (d1?.lastSeen) locationPuck(dc, { x: field.gate.x + 14, y: field.gate.y - 6 }, 3.6, { label: `${d1.operator} · ${fmt.time(d1.lastSeen)}` })
  }

  return (
    <>
      <PageHead title="Devices & sync" sub="The phones work offline all day and upload when they reach signal. This is where their records arrive, and where definitions go back out.">
        <Button variant="primary" icon={RefreshCw} loading={syncing === 'pulling'} onClick={() => void pull()}>
          {pending ? `${pending} change${pending > 1 ? 's' : ''} waiting · check now` : 'Check for changes'}
        </Button>
      </PageHead>

      <div className="dv__top">
        <div className="col gap-16">
          {devices.map((d) => <DeviceCard key={d.id} d={d} syncing={syncing === 'pulling' && d.pendingChanges > 0} progress={progress} admin={admin} onRevoke={(v) => void revoke(d.id, v)} />)}
          {devices.length === 0 && <Card pad><div className="body-s">No handsets have signed in yet. A phone registers itself the first time an operator signs in on it.</div></Card>}
        </div>
        {field && d1 && (
          <Card flush>
            <div className="dv__map"><MapView field={field} surveyed fitPad={30} animated draw={draw} coords={false} controls={false} /></div>
            <div className="row between" style={{ padding: '14px 18px', borderTop: '1px solid var(--line)' }}>
              <div><div className="title-s">Last known position</div><div className="caption">{d1.operator} · {field.name} gate · {d1.lastSeen ? `reported ${fmt.relative(d1.lastSeen)}` : 'not reported yet'}</div></div>
              <Pill tone="neutral" icon={d1.online ? Wifi : WifiOff}>{d1.online ? 'Online now' : 'No signal'}</Pill>
            </div>
          </Card>
        )}
      </div>

      <SectionHead title="Who wins a conflict" sub="Not last-write-wins" />
      <div className="dv__rules">
        <Card pad className="rule">
          <div className="row gap-12"><div className="icon-tile icon-tile--wheat"><Smartphone /></div><div><div className="title-m">The phone wins on observations</div><div className="caption">Because it was physically there</div></div></div>
          <ul><li>A scan and its photo</li><li>A zone marked treated</li><li>The product and dose applied</li><li>A quadrat count</li></ul>
        </Card>
        <Card pad className="rule">
          <div className="row gap-12"><div className="icon-tile"><RefreshCw /></div><div><div className="title-m">The dashboard wins on definitions</div><div className="caption">Because it is the deciding surface</div></div></div>
          <ul><li>Field boundaries and zone geometry</li><li>The prescription threshold</li><li>Product and label data</li><li>Model versions</li></ul>
        </Card>
      </div>

      <SectionHead title="Change log" sub="Every write on a phone is appended here with a monotonic sequence number"><Pill tone="neutral">{changeLog.length} entries</Pill></SectionHead>
      <Card flush>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th style={{ width: 70 }}>Seq</th><th>Change</th><th>Entity</th><th>Device</th><th className="num">Payload</th><th>Received</th><th>Owner</th><th /></tr></thead>
            <tbody>
              {[...changeLog].sort((a, b) => b.seq - a.seq).map((c, i) => (
                <motion.tr key={c.id} initial={i < 3 ? { opacity: 0, x: -10 } : false} animate={{ opacity: 1, x: 0 }} transition={{ delay: i * 0.05 }}>
                  <td className="mono">#{c.seq}</td>
                  <td className="title-s">{c.summary}</td>
                  <td className="mono">{c.entity}</td>
                  <td className="ink-2" style={{ fontSize: 13 }}>{c.deviceName ?? devices.find((d) => d.id === c.deviceId)?.name ?? '—'}</td>
                  <td className="num">{fmt.bytes(c.bytes)}</td>
                  <td className="ink-2">{fmt.relative(c.at)}</td>
                  <td><Pill tone={c.ownedByMobile ? 'wheat' : 'forest'}>{c.ownedByMobile ? 'Phone wins' : 'Dashboard wins'}</Pill></td>
                  <td title={c.reason}>{c.status === 'rejected' ? <X size={16} color="var(--clay)" /> : c.applied && <Check size={16} color="var(--moss)" />}</td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  )
}

function DeviceCard({ d, syncing, progress, admin, onRevoke }: { d: Device; syncing: boolean; progress: number; admin: boolean; onRevoke: (revoke: boolean) => void }) {
  const pending = d.pendingChanges
  const stale = d.lastSync != null && now() - d.lastSync > 2 * 86400000
  const Bat = d.battery < 25 ? BatteryLow : BatteryMedium
  return (
    <Card pad>
      <div className="row gap-16 start">
        <div className="dv__phone"><Smartphone size={26} /><i className={cx('dv__dot', d.online && 'is-on')} /></div>
        <div className="grow">
          <div className="row gap-8 wrap">
            <span className="title-m">{d.name}</span>
            {d.revoked && <Pill tone="clay" icon={Ban}>Revoked</Pill>}
            {pending > 0 && <Pill tone="wheat" dot>{pending} waiting</Pill>}
            {stale && <Pill tone="clay">No sync for {Math.round((now() - d.lastSync!) / 86400000)} days</Pill>}
            {d.updateAvailable && <Pill tone="slate">Update available</Pill>}
          </div>
          <div className="body-s">{d.model} · {d.os} · app {d.appVersion}</div>
          <div className="body-s">{d.operator} <span className="mono">{d.operatorId}</span></div>
        </div>
        <div className="col gap-6" style={{ alignItems: 'flex-end' }}>
          <span className="caption row gap-6"><Bat size={15} />{d.battery}%</span>
          <span className="caption row gap-6"><HardDrive size={14} />{d.storageMb} MB</span>
        </div>
      </div>
      {d.battery < 25 && <div className="mt-12"><Meter value={d.battery / 100} tone="clay" /></div>}
      <div className="dv__meta">
        <div><div className="overline">Last seen</div><div className="title-s row gap-6"><Clock size={14} />{d.lastSeen ? fmt.relative(d.lastSeen) : '—'}</div></div>
        <div><div className="overline">Last sync</div><div className="title-s">{d.lastSync ? fmt.relative(d.lastSync) : 'Never'}</div></div>
        {admin && (
          <div className="row gap-10" style={{ marginLeft: 'auto', gap: 10 }}>
            {d.revoked
              ? <Button size="sm" variant="ghost" icon={RotateCcw} onClick={() => onRevoke(false)}>Restore</Button>
              : <Button size="sm" variant="ghost" icon={Ban} onClick={() => { if (window.confirm(`Revoke ${d.name}? Its sessions end and it must sign in again.`)) onRevoke(true) }}>Revoke</Button>}
          </div>
        )}
      </div>
      {syncing && <div className="mt-12"><Meter value={progress} tone="slate" lg /><div className="caption" style={{ marginTop: 6 }}>Checking for changes…</div></div>}
      {pending > 0 && !syncing && <div className="caption mt-12">{pending} change{pending > 1 ? 's' : ''} recorded on this phone will arrive when it next has signal.</div>}
    </Card>
  )
}
