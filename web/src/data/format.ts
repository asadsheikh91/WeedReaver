import { ACRE_SQM, kanalMarla } from './geo'
import { now } from './clock'

const dayIndex = (ms: number) => {
  const d = new Date(ms)
  return Math.floor(new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() / 86400000)
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const pad = (n: number) => String(n).padStart(2, '0')

export const fmt = {
  time: (ms: number) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}` },
  dayMonth: (ms: number) => { const d = new Date(ms); return `${d.getDate()} ${MONTHS[d.getMonth()]}` },
  date: (ms: number) => { const d = new Date(ms); return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}` },
  dateTime: (ms: number) => `${fmt.date(ms)}, ${fmt.time(ms)}`,
  weekday: (ms: number) => { const d = new Date(ms); return `${WEEKDAYS[d.getDay()]}, ${d.getDate()} ${['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'][d.getMonth()]}` },
  weekdayShort: (ms: number) => { const d = new Date(ms); return `${WEEKDAYS[d.getDay()].slice(0, 3)} ${d.getDate()} ${MONTHS[d.getMonth()]}` },
  daysBetween: (from: number, to: number) => Math.round((to - from) / 86400000),

  /** "Just now", "12 min ago", "Today, 09:42", "Yesterday, 16:10", "3 days ago", "7 Jan". */
  relative(ms: number, ref = now()): string {
    const diff = ref - ms
    const dNow = dayIndex(ref), d = dayIndex(ms)
    if (diff >= 0 && diff < 60_000) return 'Just now'
    if (diff >= 60_000 && diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`
    if (d === dNow) return `Today, ${fmt.time(ms)}`
    if (d === dNow - 1) return `Yesterday, ${fmt.time(ms)}`
    if (d === dNow + 1) return `Tomorrow, ${fmt.time(ms)}`
    if (d > dNow - 7 && d < dNow) return `${dNow - d} days ago`
    return new Date(ms).getFullYear() === new Date(ref).getFullYear() ? fmt.dayMonth(ms) : fmt.date(ms)
  },

  dayGroup(ms: number, ref = now()): string {
    const dNow = dayIndex(ref), d = dayIndex(ms)
    if (d === dNow) return 'Today'
    if (d === dNow - 1) return 'Yesterday'
    const dt = new Date(ms)
    return dNow - d < 7 && d < dNow ? WEEKDAYS[dt.getDay()] : `${['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'][dt.getMonth()]} ${dt.getFullYear()}`
  },

  inDays(target: number, ref = now()): string {
    const d = dayIndex(target) - dayIndex(ref)
    if (d === 0) return 'today'
    if (d === 1) return 'tomorrow'
    if (d === -1) return 'yesterday'
    return d > 1 ? `in ${d} days` : `${-d} days ago`
  },

  acres: (ac: number) => (ac < 10 ? ac.toFixed(2) : ac.toFixed(1)),
  area: (ac: number, local = true) => (local ? `${fmt.acres(ac)} ac` : `${(ac * 0.404686).toFixed(2)} ha`),
  areaAlt(ac: number, local = true) {
    if (!local) return `${fmt.acres(ac)} ac`
    const { kanal, marla } = kanalMarla(ac)
    return marla === 0 ? `${kanal} kanal` : `${kanal} kanal ${marla} marla`
  },
  sqm: (m2: number) => (m2 >= 10_000 ? `${(m2 / 10_000).toFixed(2)} ha` : `${Math.round(m2).toLocaleString('en-GB')} m²`),
  acresFromSqm: (m2: number) => m2 / ACRE_SQM,
  meters: (m: number) => (m >= 1000 ? `${(m / 1000).toFixed(1)} km` : m >= 10 ? `${Math.round(m)} m` : `${m.toFixed(1)} m`),
  pct: (f: number) => `${Math.round(f * 100)}%`,
  int: (n: number) => Math.round(n).toLocaleString('en-GB'),
  bytes: (b: number) => (b >= 1_000_000 ? `${(b / 1_000_000).toFixed(1)} MB` : b >= 10_000 ? `${Math.round(b / 1000)} KB` : `${b} B`),
  coord: (lat: number, lon: number) => `${lat.toFixed(5)}° N, ${lon.toFixed(5)}° E`,
  plural: (n: number, one: string, many = `${one}s`) => (n === 1 ? `1 ${one}` : `${n} ${many}`),
  duration: (s: number) => (s < 60 ? `${Math.round(s)}s` : `${Math.floor(s / 60)} min${s % 60 >= 1 ? ` ${Math.round(s % 60)}s` : ''}`),
}
