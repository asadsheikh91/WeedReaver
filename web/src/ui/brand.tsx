import type { CSSProperties } from 'react'

/**
 * The mark: a single wheat leaf inside a survey reticle. The leaf is the crop the system protects; the
 * reticle is the precision it adds. Same geometry as the Android launcher icon.
 */
export function BrandMark({ size = 36, bg = '#1E4A33', ink = '#F5F0E6', style }: { size?: number; bg?: string; ink?: string; style?: CSSProperties }) {
  const arcs = [0, 1, 2, 3].map((i) => {
    const a0 = ((-70 + i * 90) * Math.PI) / 180, a1 = ((-70 + i * 90 + 50) * Math.PI) / 180
    const r = 30
    const p = (a: number) => `${(50 + r * Math.cos(a)).toFixed(2)} ${(50 + r * Math.sin(a)).toFixed(2)}`
    return `M${p(a0)} A${r} ${r} 0 0 1 ${p(a1)}`
  })
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" style={style} aria-hidden>
      <circle cx="50" cy="50" r="50" fill={bg} />
      {arcs.map((d, i) => <path key={i} d={d} fill="none" stroke={ink} strokeOpacity=".6" strokeWidth="4.5" strokeLinecap="round" />)}
      <path d="M37 67C34 48 52 33 67 31C66 47 53 63 37 67Z" fill={ink} />
      <path d="M40 64 62 36" stroke={bg} strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  )
}

export function Wordmark({ ink = 'var(--ink)', size = 30, text = 22 }: { ink?: string; size?: number; text?: number }) {
  return (
    <span className="row gap-12" style={{ color: ink }}>
      <BrandMark size={size} />
      <span className="serif" style={{ fontSize: text, letterSpacing: '-0.02em', fontVariationSettings: "'opsz' 48, 'SOFT' 40" }}>WeedReaver</span>
    </span>
  )
}
