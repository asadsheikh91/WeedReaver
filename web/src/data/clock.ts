/**
 * The dashboard's "now". The station server may pin today to a demonstration date (WR_DEMO_CLOCK_DATE), so
 * relative times ("Yesterday, 16:10", "in 4 days") are measured against the server's clock, not the browser's.
 */
let offset = 0

export const now = () => Date.now() + offset

/** Align with the server, given a server timestamp observed just now (e.g. settings.serverTime). */
export function syncClock(serverIso: string | null | undefined) {
  const t = serverIso ? Date.parse(serverIso) : NaN
  if (!Number.isNaN(t)) offset = t - Date.now()
}

/** ISO-8601 (UTC) to epoch milliseconds. Date-only strings ("2026-11-12") are read as local midnight. */
export function ms(iso: string): number
export function ms(iso: string | null | undefined): number | null
export function ms(iso: string | null | undefined): number | null {
  if (!iso) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(iso)) {
    const [y, m, d] = iso.split('-').map(Number)
    return new Date(y, m - 1, d).getTime()
  }
  return Date.parse(iso)
}
