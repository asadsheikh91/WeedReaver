import { useRef, type CSSProperties } from 'react'
import './iphone.css'

/**
 * A Pro Max class iPhone, drawn in CSS. Every measurement is in container-width units (cqw) so the device is
 * identical at 216 px and at 400 px. Proportions follow the current Pro Max body (78.0 × 163.4 mm) with a
 * 6.9" edge-to-edge display, a Dynamic Island, an Action button, volume rocker, power key and Camera Control.
 */

export type IPhoneTone = 'deep' | 'silver' | 'orange'

interface Props {
  src: string
  alt: string
  tone?: IPhoneTone
  /** Ink of the status bar; use 'light' when the top of the screen image is dark or busy. */
  bar?: 'dark' | 'light'
  className?: string
  style?: CSSProperties
  /** Pointer tilt and specular sweep. Off for phones that a parent is already transforming. */
  tilt?: boolean
  eager?: boolean
}

const Signal = () => (
  <svg viewBox="0 0 19 12" aria-hidden><rect x="0" y="8" width="3.2" height="4" rx="1" /><rect x="5" y="5.4" width="3.2" height="6.6" rx="1" /><rect x="10" y="2.7" width="3.2" height="9.3" rx="1" /><rect x="15" y="0" width="3.2" height="12" rx="1" /></svg>
)
const Wifi = () => (
  <svg viewBox="0 0 17 12" aria-hidden><path d="M8.5 2.3c2.3 0 4.4.9 6 2.4.1.1.3.1.4 0l1-1c.1-.1.1-.3 0-.4A10.4 10.4 0 0 0 8.5.3 10.4 10.4 0 0 0 1.1 3.3c-.1.1-.1.3 0 .4l1 1c.1.1.3.1.4 0a8.6 8.6 0 0 1 6-2.4Z" /><path d="M8.5 6.2c1.3 0 2.4.5 3.3 1.3.1.1.3.1.4 0l1-1c.1-.1.1-.3 0-.4a7 7 0 0 0-9.4 0c-.1.1-.1.3 0 .4l1 1c.1.1.3.1.4 0 .9-.8 2-1.3 3.3-1.3Z" /><path d="m10.7 9.1-1.9 1.9a.4.4 0 0 1-.6 0L6.3 9.1c-.1-.1-.1-.3 0-.4a3.1 3.1 0 0 1 4.4 0c.1.1.1.3 0 .4Z" /></svg>
)
const Battery = () => (
  <svg viewBox="0 0 28 13" aria-hidden><rect x=".5" y=".5" width="23.5" height="12" rx="3.7" fill="none" stroke="currentColor" strokeOpacity=".4" /><rect x="2" y="2" width="20.5" height="9" rx="2.4" /><path d="M25.5 4.4v4.2c.9-.3 1.6-1.2 1.6-2.1s-.7-1.8-1.6-2.1Z" opacity=".45" /></svg>
)

export function IPhone({ src, alt, tone = 'deep', bar = 'dark', className = '', style, tilt = true, eager }: Props) {
  const root = useRef<HTMLDivElement>(null)

  const move = (e: React.PointerEvent) => {
    if (!tilt || e.pointerType === 'touch') return
    const el = root.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height
    el.style.setProperty('--rx', `${((0.5 - y) * 9).toFixed(2)}deg`)
    el.style.setProperty('--ry', `${((x - 0.5) * 11).toFixed(2)}deg`)
    el.style.setProperty('--gx', `${(x * 100).toFixed(1)}%`)
    el.style.setProperty('--gy', `${(y * 100).toFixed(1)}%`)
    el.classList.add('is-live')
  }
  const leave = () => {
    const el = root.current
    if (!el) return
    el.classList.remove('is-live')
    el.style.removeProperty('--rx'); el.style.removeProperty('--ry')
  }

  return (
    <div ref={root} className={`ip ip--${tone} ${className}`} style={style} onPointerMove={move} onPointerLeave={leave}>
      <i className="ip__ground" aria-hidden />
      <div className="ip__tilt">
        <i className="ip__btn ip__btn--action" aria-hidden />
        <i className="ip__btn ip__btn--volup" aria-hidden />
        <i className="ip__btn ip__btn--voldn" aria-hidden />
        <i className="ip__btn ip__btn--power" aria-hidden />
        <i className="ip__btn ip__btn--camctl" aria-hidden />
        <div className="ip__frame">
          <div className="ip__bezel">
            <div className={`ip__screen ip__screen--${bar}`}>
              <img src={src} alt={alt} loading={eager ? 'eager' : 'lazy'} decoding="async" draggable={false} />
              <div className="ip__status" aria-hidden>
                <span className="ip__time">9:41</span>
                <span className="ip__icons"><Signal /><Wifi /><Battery /></span>
              </div>
              <i className="ip__island" aria-hidden />
              <i className="ip__home" aria-hidden />
              <i className="ip__glare" aria-hidden />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
