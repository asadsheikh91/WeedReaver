import { AnimatePresence, motion } from 'framer-motion'
import { Check, Info, X, type LucideIcon } from 'lucide-react'
import {
  useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState,
  type ButtonHTMLAttributes, type CSSProperties, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes,
} from 'react'
import { createPortal } from 'react-dom'
import { hracCode, type HracGroup, type Severity, type TreatmentZone } from '../data/types'
import { useStore } from '../data/store'

export type Tone = 'forest' | 'moss' | 'wheat' | 'clay' | 'slate' | 'neutral'
const cx = (...a: (string | false | null | undefined)[]) => a.filter(Boolean).join(' ')

/* ------------------------------------------------------------------ buttons */

interface BtnProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'tonal' | 'ghost' | 'danger' | 'light' | 'glass'
  size?: 'sm' | 'md' | 'lg'
  icon?: LucideIcon
  iconRight?: LucideIcon
  loading?: boolean
  block?: boolean
}
export function Button({ variant = 'secondary', size = 'md', icon: Icon, iconRight: IconR, loading, block, className, children, disabled, ...rest }: BtnProps) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx('btn', `btn--${variant}`, size !== 'md' && `btn--${size}`, block && 'btn--block', !children && 'btn--icon', className)}
    >
      {loading ? <span className="spin" /> : Icon && <Icon />}
      {children}
      {IconR && !loading && <IconR />}
    </button>
  )
}

export function IconButton({ icon: Icon, label, variant = 'ghost', size = 'md', ...rest }: BtnProps & { icon: LucideIcon; label: string }) {
  return <Button {...rest} variant={variant} size={size} icon={Icon} aria-label={label} title={label} />
}

export function LinkButton({ children, icon: Icon, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { icon?: LucideIcon }) {
  return <button {...rest} className={cx('link-btn', rest.className)}>{children}{Icon && <Icon size={14} />}</button>
}

/* ------------------------------------------------------------------ surfaces */

export function Card({ children, className, pad = false, flush = false, interactive = false, night = false, style, onClick }: {
  children: ReactNode; className?: string; pad?: boolean; flush?: boolean; interactive?: boolean; night?: boolean; style?: CSSProperties; onClick?: () => void
}) {
  return (
    <div
      className={cx('card', pad && 'card--pad', flush && 'card--flush', interactive && 'card--interactive', night && 'card--night', className)}
      style={style} onClick={onClick}
      {...(interactive && onClick ? { role: 'button', tabIndex: 0, onKeyDown: (e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } } } : {})}
    >
      {children}
    </div>
  )
}

export function CardHead({ title, children, sub }: { title: ReactNode; sub?: ReactNode; children?: ReactNode }) {
  return (
    <div className="card__head">
      <div className="grow">
        <h3>{title}</h3>
        {sub && <div className="caption" style={{ marginTop: 1 }}>{sub}</div>}
      </div>
      {children}
    </div>
  )
}

export const Hairline = ({ className }: { className?: string }) => <hr className={cx('hairline', className)} />

export function SectionHead({ title, children, info, first, sub }: { title: ReactNode; children?: ReactNode; info?: { title: string; body: ReactNode }; first?: boolean; sub?: ReactNode }) {
  return (
    <div className={cx('section-head', first && 'section-head--first')}>
      <div>
        <div className="row gap-4"><h2>{title}</h2>{info && <InfoButton {...info} />}</div>
        {sub && <div className="caption">{sub}</div>}
      </div>
      <span className="spacer" />
      {children}
    </div>
  )
}

export function InfoButton({ title, body }: { title: string; body: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button className="info-btn" aria-label={`About: ${title}`} onClick={() => setOpen(true)}><Info /></button>
      <Modal open={open} onClose={() => setOpen(false)} title={title}
        footer={<Button variant="secondary" onClick={() => setOpen(false)}>Got it</Button>}>
        <div className="col gap-12 body-s" style={{ fontSize: 14, lineHeight: '22px', color: 'var(--ink-2)' }}>{body}</div>
      </Modal>
    </>
  )
}

/* ------------------------------------------------------------------ status */

export function Pill({ tone = 'neutral', children, dot, icon: Icon, solid, lg, className }: {
  tone?: Tone; children: ReactNode; dot?: boolean; icon?: LucideIcon; solid?: boolean; lg?: boolean; className?: string
}) {
  return (
    <span className={cx('pill', `pill--${tone}`, dot && 'pill--dot', solid && 'pill--solid', lg && 'pill--lg', className)}>
      {dot && <i className="pill__dot" />}
      {Icon && <Icon />}
      {children}
    </span>
  )
}

export const severityTone = (s: Severity): Tone => (s === 'HEAVY' ? 'clay' : s === 'MODERATE' ? 'wheat' : 'moss')
export const SeverityPill = ({ s, label }: { s: Severity; label?: string }) => (
  <Pill tone={severityTone(s)}>{label ?? (s === 'HEAVY' ? 'Heavy' : s === 'MODERATE' ? 'Moderate' : 'Clean')}</Pill>
)

export function ZoneBadge({ z, size = 34 }: { z: Pick<TreatmentZone, 'letter' | 'severity' | 'state'>; size?: number }) {
  const done = z.state === 'TREATED' || z.state === 'RESURVEYED'
  const cls = done ? 'done' : z.severity === 'HEAVY' ? 'heavy' : z.severity === 'MODERATE' ? 'moderate' : 'clean'
  return (
    <span className={cx('zone-badge', `zone-badge--${cls}`)} style={{ width: size, height: size, fontSize: size * 0.4 }}>
      {done ? <Check /> : z.letter}
    </span>
  )
}

export function HracBadge({ g, warn, sm }: { g: HracGroup; warn?: boolean; sm?: boolean }) {
  return <span className={cx('hrac', warn && 'hrac--warn', sm && 'hrac--sm')}><small>HRAC</small><b>{hracCode(g)}</b></span>
}

export function CountBadge({ n, wheat }: { n: number; wheat?: boolean }) {
  if (n <= 0) return null
  return <span className={cx('count-badge', wheat && 'count-badge--wheat')}>{n > 99 ? '99+' : n}</span>
}

export function IconTile({ icon: Icon, tone = 'forest', sm }: { icon: LucideIcon; tone?: Tone; sm?: boolean }) {
  const t = tone === 'forest' || tone === 'moss' ? '' : `icon-tile--${tone}`
  return <span className={cx('icon-tile', t, sm && 'icon-tile--sm')}><Icon /></span>
}

export function Avatar({ name, size = 40, color }: { name: string; size?: number; color?: string }) {
  const ini = name.replace(/[^A-Za-z ]/g, '').split(' ').filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase()
  return <span className="avatar" style={{ width: size, height: size, fontSize: size * 0.36, background: color }}>{ini}</span>
}

export function Notice({ tone = 'wheat', icon: Icon, title, children, action }: {
  tone?: 'wheat' | 'clay' | 'forest' | 'slate'; icon?: LucideIcon; title: ReactNode; children?: ReactNode; action?: ReactNode
}) {
  return (
    <div className={cx('notice', `notice--${tone}`)}>
      {Icon && <Icon className="notice__icon" />}
      <div className="grow">
        <h4>{title}</h4>
        {children && <p>{children}</p>}
        {action}
      </div>
    </div>
  )
}

export function Empty({ icon: Icon, title, children, action }: { icon: LucideIcon; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty__icon"><Icon /></div>
      <h4>{title}</h4>
      {children && <p>{children}</p>}
      {action && <div className="mt-12">{action}</div>}
    </div>
  )
}

/* ------------------------------------------------------------------ numbers */

export function useCountUp(target: number, ms = 700, enabled = true) {
  const [v, setV] = useState(enabled ? 0 : target)
  const from = useRef(enabled ? 0 : target)
  useEffect(() => {
    if (!enabled) { setV(target); return }
    const start = performance.now(), a = from.current
    let raf = 0
    const tick = (t: number) => {
      const k = Math.min(1, (t - start) / ms)
      const e = 1 - Math.pow(1 - k, 3)
      const cur = a + (target - a) * e
      setV(cur); from.current = cur
      if (k < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [target, ms, enabled])
  return v
}

export function AnimatedNumber({ value, format = (n) => String(Math.round(n)), ms }: { value: number; format?: (n: number) => string; ms?: number }) {
  const v = useCountUp(value, ms)
  return <>{format(v)}</>
}

export function Stat({ value, label, unit, tone, sm, delta, format }: {
  value: number | string; label: ReactNode; unit?: string; tone?: Tone; sm?: boolean; delta?: ReactNode; format?: (n: number) => string
}) {
  const color = tone === 'clay' ? 'var(--clay-ink)' : tone === 'wheat' ? 'var(--wheat-ink)' : tone === 'forest' || tone === 'moss' ? 'var(--forest-2)' : undefined
  return (
    <div className={cx('stat', sm && 'stat--sm')}>
      <div className="stat__value" style={{ color }}>
        {typeof value === 'number' ? <AnimatedNumber value={value} format={format} /> : value}
        {unit && <small>{unit}</small>}
      </div>
      <div className="stat__label">{label}</div>
      {delta && <div className="stat__delta">{delta}</div>}
    </div>
  )
}

export function Meter({ value, tone, lg, mark, style }: { value: number; tone?: 'wheat' | 'clay' | 'forest' | 'slate'; lg?: boolean; mark?: number; style?: CSSProperties }) {
  const [w, setW] = useState(0)
  useEffect(() => { const id = requestAnimationFrame(() => setW(Math.min(1, Math.max(0, value)))); return () => cancelAnimationFrame(id) }, [value])
  return (
    <div className={cx('meter', lg && 'meter--lg', tone && `meter--${tone}`)} style={style}>
      <i style={{ width: `${w * 100}%` }} />
      {mark !== undefined && <span className="meter__mark" style={{ left: `${mark * 100}%` }} />}
    </div>
  )
}

export function Ring({ value, size = 72, stroke = 6, color = 'var(--moss)', children }: { value: number; size?: number; stroke?: number; color?: string; children?: ReactNode }) {
  const r = (size - stroke) / 2, c = 2 * Math.PI * r
  const [v, setV] = useState(0)
  useEffect(() => { const id = requestAnimationFrame(() => setV(value)); return () => cancelAnimationFrame(id) }, [value])
  return (
    <div className="ring" style={{ width: size, height: size }}>
      <svg viewBox={`0 0 ${size} ${size}`}>
        <circle className="ring__track" cx={size / 2} cy={size / 2} r={r} strokeWidth={stroke} />
        <circle className="ring__val" cx={size / 2} cy={size / 2} r={r} strokeWidth={stroke} stroke={color} strokeDasharray={c} strokeDashoffset={c * (1 - v)} />
      </svg>
      <div className="ring__label">{children}</div>
    </div>
  )
}

/* ------------------------------------------------------------------ controls */

export function Segmented<T extends string | number>({ options, value, onChange, size, dark, block, label }: {
  options: { value: T; label: ReactNode }[]; value: T; onChange: (v: T) => void; size?: 'sm'; dark?: boolean; block?: boolean; label?: string
}) {
  const i = Math.max(0, options.findIndex((o) => o.value === value))
  return (
    <div className={cx('seg', size === 'sm' && 'seg--sm', dark && 'seg--dark', block && 'seg--block')} role="tablist" aria-label={label}
      style={{ '--n': options.length, '--i': i } as CSSProperties}>
      <span className="seg__thumb" />
      {options.map((o) => (
        <button key={String(o.value)} role="tab" aria-selected={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  )
}

export function Chips<T extends string>({ options, value, onChange }: {
  options: { value: T; label: string; count?: number }[]; value: T; onChange: (v: T) => void
}) {
  return (
    <div className="chips">
      {options.map((o) => (
        <button key={o.value} className="chip" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}{o.count !== undefined && <small>{o.count}</small>}
        </button>
      ))}
    </div>
  )
}

export function Tabs<T extends string>({ options, value, onChange }: { options: { value: T; label: ReactNode }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {options.map((o) => <button key={o.value} className="tab" role="tab" aria-selected={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>)}
    </div>
  )
}

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return <button role="switch" aria-checked={checked} aria-label={label} className="switch" onClick={() => onChange(!checked)} />
}

export function Checkbox({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return <button role="checkbox" aria-checked={checked} aria-label={label} className="checkbox" onClick={() => onChange(!checked)}><Check /></button>
}

export function Slider({ value, min, max, step = 1, onChange, label }: { value: number; min: number; max: number; step?: number; onChange: (v: number) => void; label: string }) {
  return (
    <input type="range" className="slider" aria-label={label} min={min} max={max} step={step} value={value}
      style={{ '--p': `${((value - min) / (max - min)) * 100}%` } as CSSProperties}
      onChange={(e) => onChange(Number(e.target.value))} />
  )
}

export function Field({ label, hint, optional, children }: { label: string; hint?: ReactNode; optional?: boolean; children: ReactNode }) {
  const id = useId()
  return (
    <div className="field">
      <label className="field__label" htmlFor={id}>{label}{optional && <small>Optional</small>}</label>
      <div id={id}>{children}</div>
      {hint && <div className="field__hint">{hint}</div>}
    </div>
  )
}
export const TextInput = (p: InputHTMLAttributes<HTMLInputElement> & { sm?: boolean }) => {
  const { sm, className, ...rest } = p
  return <input {...rest} className={cx('input', sm && 'input--sm', className)} />
}
export const SelectInput = (p: SelectHTMLAttributes<HTMLSelectElement> & { sm?: boolean }) => {
  const { sm, className, ...rest } = p
  return <select {...rest} className={cx('select', sm && 'select--sm', className)} />
}

/* ------------------------------------------------------------------ overlays */

function useLockScroll(on: boolean) {
  useEffect(() => {
    if (!on) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = prev }
  }, [on])
}
function useEscape(on: boolean, fn: () => void) {
  useEffect(() => {
    if (!on) return
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') fn() }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [on, fn])
}

export function Modal({ open, onClose, title, sub, children, footer, wide }: { open: boolean; onClose: () => void; title: ReactNode; sub?: ReactNode; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  useLockScroll(open); useEscape(open, onClose)
  return createPortal(
    <AnimatePresence>
      {open && (
        <>
          <motion.div className="scrim" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.div role="dialog" aria-modal="true" className={cx('modal', wide && 'modal--wide')}
            initial={{ opacity: 0, y: 24, x: '-50%', scale: 0.97 }} animate={{ opacity: 1, y: '-50%', x: '-50%', scale: 1 }} exit={{ opacity: 0, y: '-46%', x: '-50%', scale: 0.98 }}
            transition={{ type: 'spring', stiffness: 380, damping: 34 }}>
            <div className="modal__head">
              <div className="row between start">
                <h3 className="display-s">{title}</h3>
                <IconButton icon={X} label="Close" size="sm" onClick={onClose} />
              </div>
              {sub && <div className="body-s" style={{ marginTop: 2 }}>{sub}</div>}
            </div>
            <div className="modal__body">{children}</div>
            {footer && <div className="modal__foot">{footer}</div>}
          </motion.div>
        </>
      )}
    </AnimatePresence>,
    document.body,
  )
}

export function Drawer({ open, onClose, title, sub, children, footer }: { open: boolean; onClose: () => void; title: ReactNode; sub?: ReactNode; children: ReactNode; footer?: ReactNode }) {
  useLockScroll(open); useEscape(open, onClose)
  return createPortal(
    <AnimatePresence>
      {open && (
        <>
          <motion.div className="scrim" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.aside role="dialog" aria-modal="true" className="drawer"
            initial={{ x: '108%' }} animate={{ x: 0 }} exit={{ x: '108%' }} transition={{ type: 'spring', stiffness: 320, damping: 36 }}>
            <div className="drawer__head">
              <div className="grow"><h3 className="display-s">{title}</h3>{sub && <div className="body-s" style={{ marginTop: 2 }}>{sub}</div>}</div>
              <IconButton icon={X} label="Close" size="sm" onClick={onClose} />
            </div>
            <div className="drawer__body">{children}</div>
            {footer && <div className="drawer__foot">{footer}</div>}
          </motion.aside>
        </>
      )}
    </AnimatePresence>,
    document.body,
  )
}

export function Toaster() {
  const toasts = useStore((s) => s.toasts)
  const dismiss = useStore((s) => s.dismissToast)
  return createPortal(
    <div className="toasts" aria-live="polite">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div key={t.id} layout className={cx('toast', t.tone === 'warn' && 'toast--warn')}
            initial={{ opacity: 0, y: 24, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 12, scale: 0.97 }} transition={{ type: 'spring', stiffness: 420, damping: 32 }}>
            <span className="toast__dot" />
            <span>{t.text}</span>
            {t.action && <button onClick={() => { t.onAction?.(); dismiss(t.id) }}>{t.action}</button>}
            <IconButton icon={X} label="Dismiss" size="sm" variant="ghost" style={{ color: 'rgba(245,240,230,.6)' }} onClick={() => dismiss(t.id)} />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>,
    document.body,
  )
}

/** Lightweight tooltip: shows on hover/focus after a short delay, follows the trigger. */
export function Tip({ text, children }: { text: string; children: ReactNode }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  const timer = useRef(0)
  const show = () => {
    timer.current = window.setTimeout(() => {
      const r = ref.current?.getBoundingClientRect()
      if (r) setPos({ x: r.left + r.width / 2, y: r.bottom + 8 })
    }, 350)
  }
  const hide = () => { clearTimeout(timer.current); setPos(null) }
  return (
    <span ref={ref} onMouseEnter={show} onMouseLeave={hide} onFocus={show} onBlur={hide} style={{ display: 'inline-flex' }}>
      {children}
      {pos && createPortal(<div className="tip" style={{ left: pos.x, top: pos.y, transform: 'translateX(-50%)' }}>{text}</div>, document.body)}
    </span>
  )
}

/* ------------------------------------------------------------------ hooks */

export function useHotkey(combo: string, fn: (e: KeyboardEvent) => void, deps: unknown[] = []) {
  const cb = useCallback(fn, deps) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const parts = combo.toLowerCase().split('+')
    const key = parts[parts.length - 1]
    const wantMod = parts.includes('mod')
    const h = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      if (!wantMod && (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')) return
      if (e.key.toLowerCase() === key && (!wantMod || e.ctrlKey || e.metaKey)) cb(e)
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [combo, cb])
}

export function useMeasure<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setSize({ w: e.contentRect.width, h: e.contentRect.height }))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, size] as const
}

export { cx, useMemo }
