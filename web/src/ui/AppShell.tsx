import { AnimatePresence, motion } from 'framer-motion'
import {
  BadgeCheck, ChevronRight, CloudUpload, Command, FileDown, Inbox, Layers, LayoutDashboard, PlaneTakeoff, RefreshCw,
  Search, Settings2, Smartphone, Sprout, TrendingDown, Droplets, ArrowUpRight, CornerDownLeft, LogOut, type LucideIcon,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { now } from '../data/clock'
import { hasSurvey, isDecider, useStore } from '../data/store'
import { fmt } from '../data/format'
import { warmImagery } from '../map/aerial'
import { BrandMark } from './brand'
import { Avatar, Button, CountBadge, IconButton, Pill, Toaster, useHotkey } from './kit'
import './shell.css'

interface NavItem { to: string; label: string; icon: LucideIcon; end?: boolean; badge?: 'review' | 'devices' }
const NAV: { group: string; items: NavItem[] }[] = [
  { group: 'Workspace', items: [
    { to: '/app', label: 'Overview', icon: LayoutDashboard, end: true },
    { to: '/app/fields', label: 'Fields', icon: Sprout },
    { to: '/app/weed-map', label: 'Weed map', icon: Layers },
  ] },
  { group: 'Pipeline', items: [
    { to: '/app/flights', label: 'Flights', icon: PlaneTakeoff },
    { to: '/app/review', label: 'Review queue', icon: Inbox, badge: 'review' },
    { to: '/app/treatments', label: 'Treatments', icon: Droplets },
  ] },
  { group: 'Analysis', items: [
    { to: '/app/rotation', label: 'Rotation & resistance', icon: TrendingDown },
    { to: '/app/verification', label: 'Verification', icon: BadgeCheck },
  ] },
  { group: 'Operations', items: [
    { to: '/app/exports', label: 'Exports', icon: FileDown },
    { to: '/app/devices', label: 'Devices & sync', icon: Smartphone, badge: 'devices' },
    { to: '/app/settings', label: 'Settings', icon: Settings2 },
  ] },
]

const TITLES: [RegExp, string][] = [
  [/^\/app$/, 'Overview'], [/^\/app\/fields\/.+/, 'Field'], [/^\/app\/fields/, 'Fields'], [/^\/app\/weed-map/, 'Weed map'],
  [/^\/app\/flights/, 'Flights'], [/^\/app\/review/, 'Review queue'], [/^\/app\/treatments/, 'Treatments'],
  [/^\/app\/rotation/, 'Rotation & resistance'], [/^\/app\/verification/, 'Verification'], [/^\/app\/exports/, 'Exports'],
  [/^\/app\/devices/, 'Devices & sync'], [/^\/app\/settings/, 'Settings'],
]

export function AppShell() {
  const loc = useLocation()
  const unresolved = useStore((s) => s.scans.filter((x) => x.abstained && !x.resolved).length)
  const phonePending = useStore((s) => s.phonePending)
  const threshold = useStore((s) => s.threshold)
  const pushed = useStore((s) => s.pushedThreshold)
  const syncing = useStore((s) => s.syncing)
  const pushToPhone = useStore((s) => s.pushToPhone)
  const setPalette = useStore((s) => s.setPalette)
  const status = useStore((s) => s.status)
  const loadError = useStore((s) => s.loadError)
  const me = useStore((s) => s.me)
  const station = useStore((s) => s.station)
  const signOut = useStore((s) => s.signOut)
  const nav = useNavigate()
  const scroller = useRef<HTMLElement>(null)
  const [stuck, setStuck] = useState(false)
  const decider = isDecider(me)

  useEffect(() => { void useStore.getState().bootstrap() }, [])
  useEffect(() => {
    if (status !== 'ready') return
    const s = useStore.getState()
    warmImagery(s.fields, new Set(s.fields.filter((f) => hasSurvey(s, f.id)).map((f) => f.id)))
  }, [status])
  useEffect(() => { scroller.current?.scrollTo({ top: 0 }) }, [loc.pathname])
  useHotkey('mod+k', (e) => { e.preventDefault(); setPalette(true) })

  const title = TITLES.find(([r]) => r.test(loc.pathname))?.[1] ?? 'Dashboard'
  const key = loc.pathname.split('/').slice(0, 3).join('/')
  const full = /^\/app\/weed-map/.test(loc.pathname)

  return (
    <div className="shell">
      <aside className="side">
        <Link to="/" className="side__brand" aria-label="WeedReaver home">
          <BrandMark size={34} bg="#F5F0E6" ink="#143524" />
          <span>WeedReaver</span>
        </Link>
        <nav className="side__scroll" aria-label="Primary">
          {NAV.map((g) => (
            <div key={g.group}>
              <div className="side__group">{g.group}</div>
              {g.items.map((it) => (
                <NavLink key={it.to} to={it.to} end={it.end} className="side__link" title={it.label}>
                  {({ isActive }) => (
                    <>
                      {isActive && <motion.span layoutId="side-pill" className="side__pill" transition={{ type: 'spring', stiffness: 460, damping: 38 }} />}
                      <it.icon />
                      <span className="grow">{it.label}</span>
                      {it.badge === 'review' && <CountBadge n={unresolved} wheat />}
                      {it.badge === 'devices' && <CountBadge n={phonePending} wheat />}
                    </>
                  )}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="side__foot">
          <div className="side__user">
            <Avatar name={me?.name ?? '—'} size={36} />
            <div className="grow" style={{ minWidth: 0 }}><b className="truncate">{me?.name ?? 'Signed in'}</b><small className="truncate">{me?.roleLabel ?? ''}{station ? ` · ${station.orgName.replace(' field station', '')}` : ''}</small></div>
            <IconButton icon={LogOut} label="Sign out" size="sm" variant="ghost" style={{ color: 'inherit', opacity: 0.7 }} onClick={() => { void signOut().then(() => nav('/signin', { replace: true })) }} />
          </div>
          <Link to="/" className="side__site"><ArrowUpRight /><span>Project website</span></Link>
        </div>
      </aside>

      <div className="shell__main">
        <header className="topbar" data-stuck={stuck}>
          <div className="topbar__crumb">
            <span className="overline">WeedReaver</span><ChevronRight size={14} className="sep" /><span className="title-s">{title}</span>
          </div>
          <div className="topbar__spacer" />
          <AnimatePresence>
            {threshold !== pushed && decider && status === 'ready' && (
              <motion.div initial={{ opacity: 0, scale: 0.9, x: 10 }} animate={{ opacity: 1, scale: 1, x: 0 }} exit={{ opacity: 0, scale: 0.9 }}>
                <Button variant="primary" size="sm" icon={CloudUpload} loading={syncing === 'pushing'} onClick={pushToPhone}>Publish {threshold}% threshold</Button>
              </motion.div>
            )}
          </AnimatePresence>
          <Pill tone={phonePending > 0 ? 'wheat' : 'forest'} dot>{phonePending > 0 ? `${phonePending} changes waiting on a phone` : 'Phones up to date'}</Pill>
          {station && <div className="season-chip"><b>{station.season}</b><span className="ink-3">·</span>{fmt.weekdayShort(now())}</div>}
          <button className="search-btn" onClick={() => setPalette(true)}>
            <Search /><span className="grow">Search or jump to…</span><span className="kbd">Ctrl</span><span className="kbd">K</span>
          </button>
        </header>
        <main className="page" ref={scroller} onScroll={(e) => setStuck((e.target as HTMLElement).scrollTop > 6)} data-full={full}>
          <AnimatePresence mode="wait" initial={false}>
            <motion.div key={key} className={full ? 'page__inner page__inner--full' : 'page__inner'}
              initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.28, ease: [0.2, 0, 0, 1] }}>
              {status === 'ready' ? <Outlet /> : <Boot status={status} error={loadError} />}
            </motion.div>
          </AnimatePresence>
        </main>
      </div>
      <Palette />
      <Toaster />
    </div>
  )
}

function Boot({ status, error }: { status: string; error: string | null }) {
  return (
    <div className="boot">
      {status === 'error' ? (
        <div className="empty">
          <h4>Could not load the station's data</h4>
          <p>{error}</p>
          <Button variant="primary" size="sm" icon={RefreshCw} onClick={() => void useStore.getState().bootstrap()}>Try again</Button>
        </div>
      ) : (
        <div><span className="spin" /><p className="body-s">Loading the station's fields…</p></div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ command palette */

function Palette() {
  const open = useStore((s) => s.paletteOpen)
  const setOpen = useStore((s) => s.setPalette)
  const fields = useStore((s) => s.fields)
  const nav = useNavigate()
  const [q, setQ] = useState('')
  const [i, setI] = useState(0)
  const input = useRef<HTMLInputElement>(null)

  const items = useMemo(() => {
    const go = (to: string) => () => nav(to)
    const base: { group: string; label: string; icon: LucideIcon; run: () => void; hint?: string }[] = [
      ...NAV.flatMap((g) => g.items.map((it) => ({ group: 'Go to', label: it.label, icon: it.icon, run: go(it.to) }))),
      ...fields.flatMap((f) => [
        { group: 'Fields', label: `${f.name} — field`, icon: Sprout, run: go(`/app/fields/${f.id}`), hint: f.id },
        { group: 'Fields', label: `${f.name} — weed map`, icon: Layers, run: go(`/app/weed-map/${f.id}`), hint: f.id },
      ]),
      { group: 'Actions', label: 'Check for phone changes', icon: RefreshCw, run: () => { nav('/app/devices'); void useStore.getState().pullFromPhone() } },
      { group: 'Actions', label: 'Upload a flight', icon: PlaneTakeoff, run: go('/app/flights?upload=1') },
      { group: 'Actions', label: 'Generate an export', icon: FileDown, run: go('/app/exports') },
      { group: 'Actions', label: 'Reload data from the server', icon: RefreshCw, run: () => { void useStore.getState().reload().then(() => useStore.getState().toast('Data reloaded from the station server')) } },
    ]
    const n = q.trim().toLowerCase()
    return n ? base.filter((b) => b.label.toLowerCase().includes(n) || b.group.toLowerCase().includes(n)) : base
  }, [q, fields, nav])

  useEffect(() => { if (open) { setQ(''); setI(0); setTimeout(() => input.current?.focus(), 30) } }, [open])
  useEffect(() => setI(0), [q])
  useEffect(() => {
    if (!open) return
    const h = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
      else if (e.key === 'ArrowDown') { e.preventDefault(); setI((x) => Math.min(items.length - 1, x + 1)) }
      else if (e.key === 'ArrowUp') { e.preventDefault(); setI((x) => Math.max(0, x - 1)) }
      else if (e.key === 'Enter') { e.preventDefault(); const it = items[i]; if (it) { setOpen(false); it.run() } }
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [open, items, i, setOpen])

  let lastGroup = ''
  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div className="scrim" style={{ zIndex: 95 }} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setOpen(false)} />
          <motion.div className="palette" role="dialog" aria-label="Command palette"
            initial={{ opacity: 0, y: -12, x: '-50%', scale: 0.98 }} animate={{ opacity: 1, y: 0, x: '-50%', scale: 1 }} exit={{ opacity: 0, y: -8, x: '-50%' }}
            transition={{ type: 'spring', stiffness: 420, damping: 34 }}>
            <div style={{ position: 'relative' }}>
              <Search size={20} style={{ position: 'absolute', left: 22, top: 20, color: 'var(--ink-3)' }} />
              <input ref={input} className="palette__input" placeholder="Search pages, fields and actions" value={q} onChange={(e) => setQ(e.target.value)} />
            </div>
            <div className="palette__list">
              {items.length === 0 && <div className="empty" style={{ padding: 28 }}><p>Nothing matches “{q}”.</p></div>}
              {items.map((it, idx) => {
                const head = it.group !== lastGroup
                lastGroup = it.group
                return (
                  <div key={it.group + it.label}>
                    {head && <div className="palette__group overline">{it.group}</div>}
                    <button className="palette__item" data-active={idx === i} onMouseEnter={() => setI(idx)} onClick={() => { setOpen(false); it.run() }}>
                      <it.icon /><span className="grow">{it.label}</span>{it.hint && <span className="mono">{it.hint}</span>}
                      {idx === i && <CornerDownLeft size={15} style={{ color: 'var(--ink-3)' }} />}
                    </button>
                  </div>
                )
              })}
            </div>
            <div className="palette__foot"><span className="row gap-6"><span className="kbd">↑</span><span className="kbd">↓</span> navigate</span><span className="row gap-6"><span className="kbd">↵</span> open</span><span className="row gap-6"><span className="kbd">Esc</span> close</span><span style={{ marginLeft: 'auto' }} className="row gap-6"><Command size={13} /> WeedReaver</span></div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  )
}

