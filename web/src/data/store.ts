import { create } from 'zustand'
import { api, auth, errorMessage, type SessionUser } from '../api/client'
import type {
  AuditDto, BoundaryPreviewDto, ChangeDto, DeviceDto, ExportDto, ExportPreviewDto, FieldDto, InvitationDto, PageDto, ProductDto, ScanDto,
  SpeciesDto, StationDto, SurveyDto, ThresholdDto, TreatmentDto, UserDto, ZoneDto,
} from '../api/dto'
import {
  patchesFromZones, toAudit, toChange, toDevice, toExport, toField, toProduct, toScan, toSeason, toSpecies, toSurvey,
  toTreatment, toUser, toZone,
} from './adapters'
import { syncClock } from './clock'
import { invalidate } from './queries'
import type {
  AuditEntry, ChangeLogEntry, Device, ExportJob, FieldParcel, FieldSeason, GridSize, LeafScan, Product, Species, Survey,
  SurveyRole, TreatmentRecord, TreatmentZone, UserAccount,
} from './types'

export interface Toast { id: number; text: string; tone?: 'ok' | 'warn'; action?: string; onAction?: () => void }

/** What the server says about a field's place in the loop, alongside the parcel itself. */
export interface FieldStatus { surveyed: boolean; zonesTotal: number; zonesDone: number; zonesOpen: number; pressure: string }

export interface Station {
  orgName: string; season: string; modelAerial: string; modelLeaf: string; leafModelSizeMb: number | null
  appVersion: string; thresholdMin: number; thresholdMax: number; defaultGrid: GridSize; defaultUnits: 'local' | 'metric'
  thresholdPublishedAt: number | null; thresholdPublishedBy: string | null
}

export interface ExportRequest { fieldId: string; format: ExportJob['format']; gridSize: GridSize; include: { boundary: boolean; zones: boolean; cells: boolean; abstained: boolean } }

interface State {
  status: 'idle' | 'loading' | 'ready' | 'error'
  loadError: string | null
  me: SessionUser | null
  station: Station | null
  /** The slider value. It only reaches the phones when published. */
  threshold: number
  pushedThreshold: number
  gridSize: GridSize
  units: 'local' | 'metric'
  fields: FieldParcel[]
  fieldStatus: Record<string, FieldStatus>
  seasons: FieldSeason[]
  surveys: Survey[]
  /** Published zones per field, with the state the phones reported. */
  zones: Record<string, TreatmentZone[]>
  scans: LeafScan[]
  treatments: TreatmentRecord[]
  changeLog: ChangeLogEntry[]
  audit: AuditEntry[]
  exports: ExportJob[]
  devices: Device[]
  users: UserAccount[]
  products: Product[]
  species: Species[]
  phonePending: number
  syncing: 'idle' | 'pulling' | 'pushing'
  syncProgress: number
  toasts: Toast[]
  paletteOpen: boolean

  bootstrap(): Promise<void>
  reload(): Promise<void>
  signOut(): Promise<void>
  setThreshold(v: number): void
  setGridSize(g: GridSize): void
  setUnits(u: 'local' | 'metric'): void
  saveStationDefaults(p: { defaultGridM?: GridSize; defaultUnits?: 'local' | 'metric' }): Promise<void>
  toast(text: string, opts?: Partial<Toast>): void
  fail(e: unknown, prefix?: string): void
  dismissToast(id: number): void
  setPalette(open: boolean): void

  uploadFlight(a: { fieldId: string; role: SurveyRole; files: File[] | null; simulateImages?: number; name: string; onProgress?: (f: number) => void }): Promise<string>
  resolveScan(id: string, species: string, note?: string): Promise<void>
  reopenScan(id: string): Promise<void>
  parseBoundary(file: File): Promise<BoundaryPreviewDto>
  addField(a: { name: string; village: string; boundaryLatLon: { lat: number; lon: number }[]; captureMethod: 'Drawn' | 'Imported' }): Promise<FieldParcel>
  revokeDevice(id: string, revoke: boolean): Promise<void>
  pullFromPhone(): Promise<void>
  pushToPhone(): Promise<void>
  previewExport(r: ExportRequest): Promise<ExportPreviewDto>
  addExport(r: ExportRequest): Promise<ExportJob>
  downloadExport(job: ExportJob): Promise<void>
  invite(a: { email: string; role: string; name?: string }): Promise<InvitationDto>
}

let toastId = 1
let poller: ReturnType<typeof setInterval> | null = null
let heartbeat: ReturnType<typeof setInterval> | null = null

const station = (d: StationDto): Station => ({
  orgName: d.orgName, season: d.seasonLabel, modelAerial: d.modelAerial, modelLeaf: d.modelLeaf, leafModelSizeMb: d.leafModelSizeMb,
  appVersion: d.appLatestVersion ?? '—', thresholdMin: d.thresholdMinPct, thresholdMax: d.thresholdMaxPct,
  defaultGrid: d.defaultGridM, defaultUnits: d.defaultUnits, thresholdPublishedAt: d.thresholdPublishedAt ? Date.parse(d.thresholdPublishedAt) : null,
  thresholdPublishedBy: d.thresholdPublishedBy,
})

const empty = {
  status: 'idle' as const, loadError: null, me: null, station: null, threshold: 10, pushedThreshold: 10, gridSize: 2 as GridSize,
  units: 'local' as const, fields: [], fieldStatus: {}, seasons: [], surveys: [], zones: {}, scans: [], treatments: [],
  changeLog: [], audit: [], exports: [], devices: [], users: [], products: [], species: [], phonePending: 0,
  syncing: 'idle' as const, syncProgress: 0, paletteOpen: false,
}

/** A collection that may be forbidden to this role still lets the rest of the dashboard load. */
const optional = <T,>(p: Promise<T>, fallback: T) => p.catch(() => fallback)

export const useStore = create<State>((set, get) => ({
  ...empty,
  toasts: [],

  async bootstrap() {
    if (get().status === 'loading') return
    set({ status: 'loading', loadError: null, me: auth.user })
    try {
      await get().reload()
      const s = get()
      set({ status: 'ready', threshold: s.pushedThreshold, gridSize: s.station?.defaultGrid ?? 2, units: s.station?.defaultUnits ?? 'local' })
      startPolling()
    } catch (e) {
      set({ status: auth.signedIn ? 'error' : 'idle', loadError: errorMessage(e) })
    }
  },

  async reload() {
    const [st, fieldDtos, surveys, scans, treatments, changes, audit, exports, devices, users, products, species] = await Promise.all([
      api.get<StationDto>('/settings'),
      api.get<FieldDto[]>('/fields'),
      api.get<SurveyDto[]>('/surveys'),
      api.all<ScanDto>('/scans'),
      api.all<TreatmentDto>('/treatments'),
      optional(api.get<PageDto<ChangeDto>>('/sync/changes', { limit: 200 }), { items: [] as ChangeDto[] } as PageDto<ChangeDto>),
      optional(api.get<PageDto<AuditDto>>('/audit', { limit: 50 }), { items: [] as AuditDto[] } as PageDto<AuditDto>),
      optional(api.get<ExportDto[]>('/exports'), []),
      optional(api.get<DeviceDto[]>('/devices'), []),
      optional(api.get<UserDto[]>('/users'), []),
      optional(api.get<ProductDto[]>('/products'), []),
      optional(api.get<SpeciesDto[]>('/species'), []),
    ])
    syncClock(st.serverTime)
    const zoneLists = await Promise.all(fieldDtos.map((f) => (f.surveyed
      ? api.get<ZoneDto[]>(`/fields/${f.id}/zones`, { geometry: false }).then((z) => z.map(toZone)).catch(() => [])
      : Promise.resolve([] as TreatmentZone[]))))
    const zones = Object.fromEntries(fieldDtos.map((f, i) => [f.id, zoneLists[i]]))
    const devs = devices.map(toDevice)
    const prev = get()
    const published = st.thresholdPct
    set({
      station: station(st),
      // a draft on the slider survives a reload; a fresh session starts at the published value
      threshold: prev.status === 'ready' && prev.threshold !== prev.pushedThreshold ? prev.threshold : published,
      pushedThreshold: published,
      fields: fieldDtos.map((f) => ({ ...toField(f), patches: f.surveyed ? patchesFromZones(zones[f.id]) : undefined })),
      fieldStatus: Object.fromEntries(fieldDtos.map((f) => [f.id, { surveyed: f.surveyed, zonesTotal: f.zonesTotal, zonesDone: f.zonesDone, zonesOpen: f.zonesOpen, pressure: f.pressure }])),
      seasons: fieldDtos.flatMap((f) => (f.season ? [toSeason(f.season)] : [])),
      surveys: surveys.map(toSurvey),
      zones,
      scans: scans.map(toScan),
      treatments: treatments.map(toTreatment),
      changeLog: changes.items.map(toChange),
      audit: audit.items.map(toAudit),
      exports: exports.map(toExport),
      devices: devs,
      users: users.map(toUser),
      products: products.map(toProduct),
      species: species.map(toSpecies),
      phonePending: devs.reduce((n, d) => n + d.pendingChanges, 0),
    })
  },

  async signOut() {
    stopPolling()
    invalidate()
    set({ ...empty })
    await auth.signOut()
  },

  setThreshold: (v) => {
    const st = get().station
    set({ threshold: Math.min(st?.thresholdMax ?? 40, Math.max(st?.thresholdMin ?? 2, Math.round(v))) })
  },
  setGridSize: (g) => set({ gridSize: g }),
  setUnits: (u) => set({ units: u }),
  async saveStationDefaults(p) {
    if (p.defaultGridM) set({ gridSize: p.defaultGridM })
    if (p.defaultUnits) set({ units: p.defaultUnits })
    try {
      const st = await api.patch<StationDto>('/settings', p)
      set({ station: station(st) })
      void refreshAudit()
    } catch (e) { get().fail(e, 'Settings not saved') }
  },

  toast(text, opts = {}) {
    const id = toastId++
    set((s) => ({ toasts: [...s.toasts.slice(-2), { id, text, ...opts }] }))
    setTimeout(() => get().dismissToast(id), opts.action ? 6000 : 3800)
  },
  fail(e, prefix) { get().toast(prefix ? `${prefix}: ${errorMessage(e)}` : errorMessage(e), { tone: 'warn' }) },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  setPalette: (open) => set({ paletteOpen: open }),

  async uploadFlight({ fieldId, role, files, simulateImages, name, onProgress }) {
    // 1 open (or reuse the scheduled) flight, 2 send images in batches, 3 queue processing
    const opened = await api.post<SurveyDto>('/surveys/uploads', { fieldId, role })
    upsertSurvey(opened)
    if (files?.length) {
      const total = files.reduce((n, f) => n + f.size, 0) || 1
      let sent = 0
      for (const batch of batches(files, 24, 96e6)) {
        const form = new FormData()
        batch.forEach((f) => form.append('files', f, f.name))
        const size = batch.reduce((n, f) => n + f.size, 0)
        const r = await api.upload<{ received: number; rejected: { file: string; reason: string }[] }>(`/surveys/${opened.id}/images`, form,
          (k) => onProgress?.((sent + k * size) / total))
        sent += size
        if (r.rejected.length) get().toast(`${r.rejected.length} file${r.rejected.length > 1 ? 's' : ''} skipped: ${r.rejected[0].reason}`, { tone: 'warn' })
      }
    }
    await api.post(`/surveys/${opened.id}/process`, files?.length ? { source: name } : { simulateImages, source: name })
    upsertSurvey(await api.get<SurveyDto>(`/surveys/${opened.id}`))
    startPolling()
    void refreshAudit()
    invalidate('overview', 'season')
    return opened.id
  },

  async resolveScan(id, species, note) {
    try {
      const s = await api.post<ScanDto>(`/scans/${id}/resolve`, { species, note: note || null })
      replaceScan(s)
      get().toast(`${id} labelled ${species}`, { tone: 'ok', action: 'Undo', onAction: () => void get().reopenScan(id) })
      invalidate('overview')
      void refreshAudit()
    } catch (e) { get().fail(e, `${id} not labelled`) }
  },
  async reopenScan(id) {
    try {
      replaceScan(await api.post<ScanDto>(`/scans/${id}/reopen`))
      invalidate('overview')
      void refreshAudit()
    } catch (e) { get().fail(e, `${id} not reopened`) }
  },

  parseBoundary(file) {
    const form = new FormData()
    form.append('file', file, file.name)
    return api.upload<BoundaryPreviewDto>('/fields/parse-boundary', form)
  },
  async addField({ name, village, boundaryLatLon, captureMethod }) {
    const d = await api.post<FieldDto>('/fields', {
      clientId: crypto.randomUUID(), name, village: village || null, captureMethod, boundaryLatLon,
    })
    await get().reload()
    invalidate('overview', 'season')
    return get().fields.find((f) => f.id === d.id) ?? toField(d)
  },

  async revokeDevice(id, revoke) {
    try {
      const d = await api.post<DeviceDto>(`/devices/${id}/${revoke ? 'revoke' : 'restore'}`)
      set((s) => ({ devices: s.devices.map((x) => (x.id === id ? toDevice(d) : x)) }))
      get().toast(revoke ? `${d.name} revoked. Its sessions are ended.` : `${d.name} can sign in again`, { tone: 'ok' })
      void refreshAudit()
    } catch (e) { get().fail(e) }
  },

  /** Phones push when they have signal; the dashboard can only look for what has arrived. */
  async pullFromPhone() {
    if (get().syncing !== 'idle') return
    set({ syncing: 'pulling', syncProgress: 0.3 })
    const before = new Set(get().changeLog.map((c) => c.id))
    try {
      await get().reload()
      invalidate('overview', 'verification', 'review')
      const fresh = get().changeLog.filter((c) => !before.has(c.id))
      get().toast(fresh.length ? `${fmtCount(fresh.length, 'change')} from the phones applied` : 'Nothing new from the phones yet. Changes arrive when a handset has signal.', { tone: fresh.length ? 'ok' : undefined })
    } catch (e) { get().fail(e, 'Could not refresh') }
    set({ syncing: 'idle', syncProgress: 0 })
  },

  async pushToPhone() {
    const s = get()
    if (s.syncing !== 'idle' || s.pushedThreshold === s.threshold) return
    set({ syncing: 'pushing', syncProgress: 0.5 })
    try {
      const r = await api.put<ThresholdDto>('/settings/threshold', { thresholdPct: s.threshold })
      set({ pushedThreshold: r.thresholdPct, threshold: r.thresholdPct })
      await get().reload()
      invalidate('overview', 'zones-preview', 'verification', 'review', 'rotation')
      get().toast(`Threshold ${r.previousPct}% → ${r.thresholdPct}% published. ${fmtCount(r.fieldsRezoned, 'field')} re-zoned; phones pick it up on their next sync.`, { tone: 'ok' })
    } catch (e) { get().fail(e, 'Threshold not published') }
    set({ syncing: 'idle', syncProgress: 0 })
  },

  previewExport(r) {
    return api.post('/exports/preview', { fieldId: r.fieldId, format: r.format, gridSize: r.gridSize, include: r.include })
  },
  async addExport(r) {
    const d = await api.post<ExportDto>('/exports', { fieldId: r.fieldId, format: r.format, gridSize: r.gridSize, include: r.include })
    const job = toExport(d)
    set((s) => ({ exports: [job, ...s.exports.filter((x) => x.id !== job.id)] }))
    void refreshAudit()
    return job
  },
  async downloadExport(job) {
    await api.download(job.downloadUrl ?? `/exports/${job.id}/download`, job.filename)
  },

  async invite({ email, role, name }) {
    const inv = await api.post<InvitationDto>('/users/invitations', { email, role, name: name || null })
    void refreshAudit()
    return inv
  },
}))

// ---- helpers that write into the store ---------------------------------------------------------

function upsertSurvey(d: SurveyDto) {
  const s = toSurvey(d)
  useStore.setState((st) => ({ surveys: st.surveys.some((x) => x.id === s.id) ? st.surveys.map((x) => (x.id === s.id ? s : x)) : [...st.surveys, s] }))
}

function replaceScan(d: ScanDto) {
  const s = toScan(d)
  useStore.setState((st) => ({ scans: st.scans.map((x) => (x.id === s.id ? s : x)) }))
}

async function refreshAudit() {
  try {
    const a = await api.get<PageDto<AuditDto>>('/audit', { limit: 50 })
    useStore.setState({ audit: a.items.map(toAudit) })
  } catch { /* not every role reads the audit log */ }
}

function* batches(files: File[], count: number, bytes: number) {
  let cur: File[] = [], size = 0
  for (const f of files) {
    if (cur.length && (cur.length >= count || size + f.size > bytes)) { yield cur; cur = []; size = 0 }
    cur.push(f); size += f.size
  }
  if (cur.length) yield cur
}

const fmtCount = (n: number, one: string) => `${n} ${one}${n === 1 ? '' : 's'}`

/** Flights that are queued or processing are polled until they finish; then everything they change is refetched. */
function startPolling() {
  if (!poller) {
    poller = setInterval(async () => {
      const live = useStore.getState().surveys.filter((s) => s.status === 'PROCESSING' || s.status === 'QUEUED')
      if (!live.length) return
      for (const s of live) {
        try {
          const d = await api.get<SurveyDto>(`/surveys/${s.id}`)
          upsertSurvey(d)
          if (d.status === 'READY' || d.status === 'FAILED') {
            const st = useStore.getState()
            await st.reload().catch(() => {})
            invalidate('grid/', 'stats/', 'compare/', 'zones-preview/', 'verification/', 'overview', 'season', 'review', 'rotation/')
            st.toast(d.status === 'READY'
              ? `${d.fieldName}: ${d.role === 'PRE' ? 'flight processed, weed map updated' : `${d.role === 'PLUS_14D' ? '+14 d' : '+28 d'} flight processed. Verification is ready.`}`
              : `${d.fieldName}: processing failed. ${d.error ?? ''}`, { tone: d.status === 'READY' ? 'ok' : 'warn' })
          }
        } catch { /* try again on the next tick */ }
      }
      invalidate('overview')
    }, 2500)
  }
  if (!heartbeat) {
    // devices, the sync ledger and the review queue change when phones push; look every half minute
    heartbeat = setInterval(async () => {
      try {
        const [devices, changes] = await Promise.all([api.get<DeviceDto[]>('/devices'), api.get<PageDto<ChangeDto>>('/sync/changes', { limit: 200 })])
        const devs = devices.map(toDevice)
        const known = new Set(useStore.getState().changeLog.map((c) => c.id))
        const fresh = changes.items.some((c) => !known.has(c.id))
        useStore.setState({ devices: devs, phonePending: devs.reduce((n, d) => n + d.pendingChanges, 0), changeLog: changes.items.map(toChange) })
        if (fresh) { await useStore.getState().reload(); invalidate('overview', 'verification', 'review') }
      } catch { /* offline or signed out */ }
    }, 30_000)
  }
}

function stopPolling() {
  if (poller) clearInterval(poller)
  if (heartbeat) clearInterval(heartbeat)
  poller = heartbeat = null
}

// ---- selectors ------------------------------------------------------------------------------

export const useField = (id: string | undefined) => useStore((s) => s.fields.find((f) => f.id === id))

export function seasonOf(s: Pick<State, 'seasons'>, fieldId: string) {
  return s.seasons.find((x) => x.fieldId === fieldId)
}
export function surveysOf(s: Pick<State, 'seasons' | 'surveys'>, fieldId: string) {
  const fs = seasonOf(s, fieldId)
  return s.surveys.filter((x) => (x.fieldId ? x.fieldId === fieldId && (!fs || x.fieldSeasonId === fs.id) : x.fieldSeasonId === fs?.id)).sort((a, b) => a.flownAt - b.flownAt)
}
export function hasSurvey(s: Pick<State, 'seasons' | 'surveys'>, fieldId: string) {
  return surveysOf(s, fieldId).some((x) => x.role === 'PRE' && x.status === 'READY')
}

export const isDecider = (u: SessionUser | null) => u?.role === 'ANALYST' || u?.role === 'ADMIN'
