import { motion } from 'framer-motion'
import { useId, useMemo } from 'react'
import { useMeasure } from './kit'

/**
 * Season-over-season control for one mode of action. The dashed line marks the level below which the
 * agronomist treats a population as suspect, so the reader does not need to know what "47%" means to
 * see that it is bad.
 */
export function TrendChart({ values, labels, threshold = 70, height = 260, color = 'var(--clay)' }: {
  values: number[]; labels: string[]; threshold?: number; height?: number; color?: string
}) {
  const [ref, { w }] = useMeasure<HTMLDivElement>()
  const gid = useId()
  const L = 46, R = 36, T = 40, B = 40
  const iw = Math.max(10, w - L - R), ih = height - T - B
  const y = (v: number) => T + ih * (1 - v / 100)
  const step = values.length > 1 ? iw / (values.length - 1) : 0
  const pts = values.map((v, i) => [L + i * step, y(v)] as const)
  const line = pts.map(([x, yy], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${yy.toFixed(1)}`).join(' ')
  const area = `${line} L${pts[pts.length - 1]?.[0] ?? L} ${T + ih} L${L} ${T + ih} Z`
  return (
    <div ref={ref} style={{ width: '100%' }}>
      {w > 0 && (
        <svg width={w} height={height} role="img" aria-label={`Control trend: ${values.map((v, i) => `${labels[i]} ${v}%`).join(', ')}`}>
          <defs>
            <linearGradient id={gid} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor={color} stopOpacity=".22" /><stop offset="1" stopColor={color} stopOpacity="0" />
            </linearGradient>
          </defs>
          {[0, 50, 100].map((g) => (
            <g key={g}>
              <line x1={L} x2={L + iw} y1={y(g)} y2={y(g)} stroke="var(--line)" />
              <text x={L - 10} y={y(g) + 4} textAnchor="end" fontSize="11" fill="var(--ink-3)">{g}%</text>
            </g>
          ))}
          <line x1={L} x2={L + iw} y1={y(threshold)} y2={y(threshold)} stroke="var(--moss)" strokeWidth="1.5" strokeDasharray="7 6" />
          <text x={iw < 420 ? L + 4 : L + iw} y={y(threshold) + (iw < 420 ? 17 : -8)} textAnchor={iw < 420 ? 'start' : 'end'} fontSize="11" fontWeight="600" fill="var(--moss)">Acceptable control · {threshold}%</text>
          <motion.path d={area} fill={`url(#${gid})`} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.8, delay: 0.4 }} />
          <motion.path d={line} fill="none" stroke={color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"
            initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ duration: 1, ease: [0.2, 0, 0, 1] }} />
          {pts.map(([x, yy], i) => (
            <motion.g key={i} initial={{ opacity: 0, scale: 0.5 }} animate={{ opacity: 1, scale: 1 }} transition={{ delay: 0.25 + i * 0.28, type: 'spring', stiffness: 400, damping: 22 }} style={{ transformOrigin: `${x}px ${yy}px` }}>
              <circle cx={x} cy={yy} r="6" fill="var(--ivory)" stroke={color} strokeWidth="3" />
              <text x={x} y={yy - 16} textAnchor="middle" fontSize="14" fontWeight="700" fill={color} style={{ fontFeatureSettings: '"tnum"' }}>{values[i]}%</text>
            </motion.g>
          ))}
          {labels.map((l, i) => (
            <text key={l} x={Math.min(L + iw, Math.max(L, L + i * step))} y={height - 12} textAnchor={i === 0 ? 'start' : i === labels.length - 1 ? 'end' : 'middle'} fontSize="12" fill="var(--ink-3)">{l}</text>
          ))}
        </svg>
      )}
    </div>
  )
}

/** Two stacked bars: cover before (clay) and after (moss) on a shared scale. */
export function BeforeAfterBar({ before, after, scale }: { before: number; after: number; scale: number }) {
  return (
    <div className="col gap-4" style={{ width: '100%' }}>
      <div className="meter meter--lg meter--clay" style={{ opacity: 0.85 }}><i style={{ width: `${Math.max(2, (before / scale) * 100)}%` }} /></div>
      <div className="meter meter--lg"><i style={{ width: `${Math.max(2, (after / scale) * 100)}%` }} /></div>
    </div>
  )
}

export function Sparkline({ values, width = 96, height = 30, color = 'var(--moss)' }: { values: number[]; width?: number; height?: number; color?: string }) {
  const path = useMemo(() => {
    const min = Math.min(...values), max = Math.max(...values)
    const span = max - min || 1
    return values.map((v, i) => `${i ? 'L' : 'M'}${((i / (values.length - 1)) * (width - 4) + 2).toFixed(1)} ${(height - 3 - ((v - min) / span) * (height - 6)).toFixed(1)}`).join(' ')
  }, [values, width, height])
  return (
    <svg width={width} height={height} aria-hidden>
      <path d={path} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** Horizontal stacked composition bar with a legend. */
export function StackBar({ parts }: { parts: { label: string; value: number; color: string }[] }) {
  const total = parts.reduce((s, p) => s + p.value, 0) || 1
  return (
    <div className="col gap-8">
      <div className="row" style={{ height: 10, borderRadius: 5, overflow: 'hidden', gap: 2 }}>
        {parts.map((p) => <motion.div key={p.label} initial={{ width: 0 }} animate={{ width: `${(p.value / total) * 100}%` }} transition={{ duration: 0.7, ease: [0.2, 0, 0, 1] }} style={{ background: p.color, height: '100%' }} />)}
      </div>
      <div className="row gap-16 wrap">
        {parts.map((p) => <span key={p.label} className="caption row gap-6"><i style={{ width: 8, height: 8, borderRadius: 2, background: p.color, display: 'inline-block' }} />{p.label} {p.value}</span>)}
      </div>
    </div>
  )
}
