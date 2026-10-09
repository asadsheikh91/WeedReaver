import type {
  AuditDto, ChangeDto, DeviceDto, ExportDto, FieldDto, GridDto, ProductDto, ScanDto, SeasonDto, SpeciesDto, SurveyDto,
  TreatmentDto, UserDto, ZoneDto,
} from '../api/dto'
import { ms } from './clock'
import type {
  AuditEntry, ChangeLogEntry, Device, ExportJob, FieldParcel, FieldSeason, GridCell, Patch, Product, RasterGrid, LeafScan,
  Species, Survey, TreatmentRecord, TreatmentZone, UserAccount, WeedClass,
} from './types'

/** API responses to the dashboard's own types: ISO timestamps become epoch milliseconds. */

export const toField = (d: FieldDto): FieldParcel => ({
  id: d.id, name: d.name, village: d.village, boundary: d.boundary, lat: d.lat, lon: d.lon,
  capturedBy: d.capturedBy, landscape: { ...d.landscape, mustardBias: d.landscape?.mustardBias ?? 0.12 }, gate: d.gate, captureMethod: d.captureMethod,
  areaAcres: d.area?.acres,
})

export const toSeason = (d: SeasonDto): FieldSeason => ({
  id: d.id, fieldId: d.fieldId, crop: d.crop, season: d.season, sowingDate: ms(d.sowingDate) ?? 0,
  rowSpacingCm: d.rowSpacingCm ?? 0, variety: d.variety ?? '', harvestDate: ms(d.harvestDate),
})

export const toSurvey = (d: SurveyDto): Survey => ({
  id: d.id, fieldSeasonId: d.fieldSeasonId, fieldId: d.fieldId, flownAt: ms(d.flownAt), role: d.role, altitudeM: d.altitudeM,
  sensor: d.sensor, gsdCm: d.gsdCm, status: d.status, images: d.images, progress: d.progress,
  stage: d.stage ?? undefined, source: d.source ?? undefined, error: d.error ?? d.job?.error ?? undefined,
})

export const toZone = (d: ZoneDto): TreatmentZone => ({
  id: d.code, label: d.label, letter: d.letter, severity: d.severity, dominantClass: d.dominantClass,
  areaSqm: d.areaSqm, cellCount: d.cellCount, cx: d.cx, cy: d.cy, radiusM: d.radiusM, distanceM: d.distanceM,
  state: d.state ?? 'FLAGGED', meanInfestPct: d.meanInfestPct, efficacyPct: d.efficacyPct ?? undefined,
  treatedAt: ms(d.treatedAt) ?? undefined,
})

/** Patches for the imagery renderer, so the canopy shows weeds where the published zones are. */
export const patchesFromZones = (zones: TreatmentZone[]): Patch[] => zones.map((z) => ({
  cx: z.cx, cy: z.cy, radiusM: Math.max(4, z.radiusM * 1.35), peak: Math.min(0.95, (z.meanInfestPct / 100) * 2.6),
  weedClass: z.dominantClass, label: z.letter, stretch: 1.35,
}))

const CLASS: Record<string, WeedClass> = { C: 'CROP', G: 'GRASS', B: 'BROADLEAF' }

/** Decodes the columnar grid (row-major arrays, flags bit 1 inside, 2 treated, 4 abstained). */
export function toGrid(d: GridDto): RasterGrid {
  const n = d.cols * d.rows
  const cells: GridCell[] = new Array(n)
  for (let i = 0; i < n; i++) {
    const f = d.flags[i]
    cells[i] = {
      col: i % d.cols, row: Math.floor(i / d.cols), infestPct: d.infestPct[i], weedClass: CLASS[d.classes[i]] ?? 'CROP',
      confidence: d.confidence[i], inside: (f & 1) !== 0, treated: (f & 2) !== 0, abstained: (f & 4) !== 0,
    }
  }
  return {
    cols: d.cols, rows: d.rows, cellMeters: d.cellMeters, originX: d.originX, originY: d.originY, cells,
    total: d.stats.total, flagged: d.stats.flagged, abstained: d.stats.abstained,
    treatedFraction: d.stats.treatedFraction, treatedSqm: d.stats.treatedSqm,
  }
}

const seedOf = (id: string) => [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7) % 997

export const toScan = (d: ScanDto): LeafScan => ({
  id: d.id, at: ms(d.at), speciesLatin: d.speciesLatin, speciesLocal: d.speciesLocal ?? '', weedClass: d.weedClass,
  confidence: d.confidence, abstained: d.abstained, fieldId: d.fieldId, fieldName: d.fieldName,
  zoneLabel: d.zoneLabel ?? undefined, frameId: d.frameId ?? undefined, lat: d.lat, lon: d.lon,
  gnssAccuracyM: d.gnssAccuracyM ?? 0, modelVersion: d.modelVersion ?? 'unknown model', synced: d.synced,
  leafSeed: d.leafSeed ?? seedOf(d.id), runnerUp: d.runnerUp, inferenceMs: d.inferenceMs ?? 0,
  resolved: d.resolved, annotation: d.resolution ?? undefined, resolvedBy: d.resolvedBy ?? undefined,
  resolvedAt: ms(d.resolvedAt) ?? undefined, note: d.resolutionNote ?? undefined, deviceId: d.deviceId ?? '',
  deviceName: d.deviceName ?? undefined, hasPhoto: d.hasPhoto, suggestion: d.annotation ?? undefined,
})

export const toTreatment = (d: TreatmentDto): TreatmentRecord => ({
  id: d.id, fieldSeasonId: d.fieldSeasonId, fieldId: d.fieldId, fieldName: d.fieldName, zoneLabels: d.zoneLabels,
  appliedAt: ms(d.appliedAt), product: d.product, activeIngredient: d.activeIngredient, hracGroup: d.hracGroup,
  doseRecorded: d.doseRecorded, doseUnit: d.doseUnit, applicationMode: d.applicationMode, growthStage: d.growthStage,
  operator: d.operator, areaAcres: d.areaAcres, synced: d.synced, waterLitres: d.waterLitres, notes: d.notes,
})

export const toProduct = (d: ProductDto): Product => ({
  trade: d.trade, active: d.active, hrac: d.hrac, target: d.target, crop: d.crop, formulation: d.formulation, registered: d.registered,
})

export const toSpecies = (d: SpeciesDto): Species => ({ ...d })

export const toChange = (d: ChangeDto): ChangeLogEntry => ({
  id: d.id, seq: d.seq, entity: d.entity, summary: d.summary, at: ms(d.receivedAt ?? d.at), ownedByMobile: d.ownedByMobile,
  bytes: d.bytes, deviceId: d.deviceId ?? '', applied: d.applied, deviceName: d.deviceName ?? undefined, status: d.status,
  reason: d.reason ?? undefined,
})

export const toDevice = (d: DeviceDto): Device => ({
  id: d.id, name: d.name, model: d.model ?? '—', os: d.os ?? '—', operator: d.operator ?? 'Unassigned',
  operatorId: d.operatorCode ?? d.operatorId ?? '', appVersion: d.appVersion ?? '—', battery: d.battery ?? 0,
  lastSeen: ms(d.lastSeenAt), lastSync: ms(d.lastSyncAt), online: d.online, storageMb: d.storageMb ?? 0,
  pendingChanges: d.pendingChanges, revoked: d.revoked, updateAvailable: d.updateAvailable,
})

export const toUser = (d: UserDto): UserAccount => ({
  id: d.id, name: d.name, role: d.roleLabel as UserAccount['role'], email: d.email, lastActive: ms(d.lastActiveAt) ?? 0,
  scope: d.scopeLabel,
})

export const toExport = (d: ExportDto): ExportJob => ({
  id: d.id, fieldId: d.fieldId, format: d.format, at: ms(d.at), sizeKb: d.sizeKb, cells: d.cells, zones: d.zones,
  threshold: d.thresholdPct, gridSize: d.gridSize, filename: d.filename, downloadUrl: d.downloadUrl,
})

export const toAudit = (d: AuditDto): AuditEntry => ({ id: d.id, at: ms(d.at), who: d.who, action: d.action, detail: d.detail })
