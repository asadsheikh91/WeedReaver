/**
 * Wire shapes of the API responses the dashboard reads (camelCase, ISO-8601 UTC timestamps).
 * Only the fields the dashboard uses are typed; the adapters in ../data/adapters.ts turn them into the
 * app's own types (epoch milliseconds, the seed-era field names).
 */
import type {
  GridSize, HracGroup, Landscape, Pt, Severity, SurveyRole, SurveyStatus, WeedClass, ZoneState,
} from '../data/types'

export type Iso = string

export interface PageDto<T> { items: T[]; total: number; limit: number; offset: number }

export interface SeasonDto {
  id: string; fieldId: string; crop: string; season: string; sowingDate: string | null
  rowSpacingCm: number | null; variety: string | null; harvestDate: string | null; daysSinceSowing: number | null
}

export interface SurveyBriefDto { id: string; role: SurveyRole; status: SurveyStatus; flownAt: Iso; progress: number }

export interface AreaDto { sqm: number; acres: number; hectares: number; kanal: number; marla: number; perimeterM: number }

export interface FieldDto {
  id: string; name: string; village: string; boundary: Pt[]; lat: number; lon: number; gate: Pt
  landscape: Landscape; captureMethod: string; capturedBy: string; area: AreaDto; archived: boolean
  season: SeasonDto | null; surveyed: boolean; pressure: Severity
  zonesTotal: number; zonesDone: number; zonesOpen: number
  lastSurvey: SurveyBriefDto | null; nextSurvey: SurveyBriefDto | null; verifiedAt: Iso | null
  surveys?: SurveyBriefDto[]; upNext?: string
}

export interface BoundaryPreviewDto {
  boundary: Pt[]; boundaryLatLon: { lat: number; lon: number }[]; lat: number; lon: number
  area: AreaDto; vertices: number; sourceFormat: 'KML' | 'GeoJSON' | 'CSV'; name: string | null
}

export interface JobDto { id: string; status: string; stage: string | null; progress: number; error: string | null }

export interface SurveyDto {
  id: string; fieldSeasonId: string; fieldId: string; fieldName: string; role: SurveyRole; status: SurveyStatus
  flownAt: Iso; altitudeM: number; sensor: string; gsdCm: number; images: number; imageBytes: number
  progress: number; stage: string | null; source: string | null; error: string | null
  pipeline: string | null; modelVersion: string | null; job: JobDto | null
}

export interface ZoneDto {
  id?: string; code: string; label: string; letter: string; severity: Severity; dominantClass: WeedClass
  areaSqm: number; cellCount: number; cx: number; cy: number; radiusM: number; distanceM: number
  routeOrder: number; meanInfestPct: number; state?: ZoneState; treatedAt?: Iso | null; efficacyPct?: number | null
}

export interface GridStatsDto {
  cellMeters: GridSize; thresholdPct: number; total: number; flagged: number; abstained: number
  treatedFraction: number; treatedSqm: number; treatedAcres: number
}

export interface GridDto {
  fieldId: string; surveyId: string; survey: SurveyRole; cols: number; rows: number; cellMeters: GridSize
  originX: number; originY: number; thresholdPct: number; stats: GridStatsDto
  infestPct: number[]; confidence: number[]; classes: string; flags: number[]
}

export interface GridCompareDto { fieldId: string; thresholdPct: number; grids: GridStatsDto[]; note: string }

export interface ZoneEfficacyDto { letter: string; label: string; beforePct: number; afterPct: number; efficacyPct: number; inspect: boolean }

export interface VerificationDto {
  fieldId: string; role: 'PLUS_14D' | 'PLUS_28D'; ready: boolean; reason: string | null
  followStatus: SurveyStatus | null; followProgress: number | null; followFlownAt: Iso | null
  zones: ZoneEfficacyDto[]; worst: ZoneEfficacyDto | null; weakCount: number; fieldDeltaPct: number | null
  chemicalSavedFraction: number | null; beforeFlagged: number | null; afterFlagged: number | null; caveat: string
}

export interface RotationProductDto {
  id: string; trade: string; active: string; hrac: HracGroup; hracDisplay: string; target: WeedClass; crop: string; formulation: string
}

export interface RotationDto {
  fieldId: string
  seasons: { season: string; hracGroups: HracGroup[]; treatments: string[]; controlPct: number | null }[]
  trend: { season: string; hrac: HracGroup; controlPct: number }[]
  latestGroup: HracGroup | null; streak: number; risk: 'High' | 'Watch' | 'Low'; acceptableControlPct: number
  warning: string | null; usedGroups: HracGroup[]; alternatives: RotationProductDto[]; nextSeason: string
}

export interface ScanDto {
  id: string; at: Iso; fieldId: string; fieldName: string; zoneLabel: string | null; frameId: string | null
  speciesLatin: string; speciesLocal: string | null; weedClass: WeedClass; confidence: number; abstained: boolean
  runnerUp: [string, number][]; inferenceMs: number | null; modelVersion: string | null; leafSeed: number | null
  lat: number | null; lon: number | null; gnssAccuracyM: number | null; deviceId: string | null; deviceName: string | null
  hasPhoto: boolean; synced: boolean; annotation: string | null; annotationNote: string | null; annotatedBy: string | null
  resolved: boolean; resolution: string | null; resolutionNote: string | null; resolvedBy: string | null; resolvedAt: Iso | null
}

export interface TreatmentDto {
  id: string; fieldSeasonId: string; fieldId: string; fieldName: string; zoneLabels: string[]; appliedAt: Iso
  product: string; activeIngredient: string; hracGroup: HracGroup; doseRecorded: string; doseUnit: string
  applicationMode: string; growthStage: string; operator: string; areaAcres: number; synced: boolean
  waterLitres: string; notes: string; season: string
}

export interface ProductDto {
  id: string; trade: string; active: string; hrac: HracGroup; target: WeedClass; crop: string; formulation: string; registered: boolean
}

export interface SpeciesDto { latin: string; local: string; common: string; cls: WeedClass; note: string }

export interface ChangeDto {
  id: string; seq: number; entity: string; op: string; summary: string; at: Iso; receivedAt: Iso
  ownedByMobile: boolean; bytes: number; deviceId: string | null; deviceName: string | null
  status: 'applied' | 'duplicate' | 'rejected'; applied: boolean; reason: string | null
}

export interface DeviceDto {
  id: string; name: string; model: string | null; os: string | null; appVersion: string | null
  operator: string | null; operatorId: string | null; operatorCode: string | null; battery: number | null
  storageMb: number | null; pendingChanges: number; lastSeenAt: Iso | null; lastSyncAt: Iso | null
  online: boolean; revoked: boolean; updateAvailable: boolean
}

export interface UserDto {
  id: string; email: string; name: string; role: 'ADMIN' | 'ANALYST' | 'OPERATOR' | 'TRAINEE'; roleLabel: string
  isActive: boolean; scopeLabel: string; lastActiveAt: Iso | null
}

export interface InvitationDto { id: string; email: string; role: string; status: string; link: string | null; expiresAt: Iso }

export interface ExportDto {
  id: string; fieldId: string; fieldName: string; format: 'GeoJSON' | 'Shapefile' | 'TASKDATA'; gridSize: GridSize
  thresholdPct: number; sizeBytes: number; sizeKb: number; cells: number; zones: number; filename: string; at: Iso
  createdBy: string | null; downloadUrl: string
}

export interface ExportPreviewDto { filename: string; format: string; sizeBytes: number; cells: number; zones: number; lines: number; preview: string }

export interface AuditDto { id: string; at: Iso; who: string; action: string; detail: string }

export interface StationDto {
  orgName: string; seasonLabel: string; thresholdPct: number; thresholdPublishedAt: Iso | null; thresholdPublishedBy: string | null
  thresholdMinPct: number; thresholdMaxPct: number; defaultGridM: GridSize; defaultUnits: 'local' | 'metric'
  modelAerial: string; modelLeaf: string; leafModelSizeMb: number | null; appLatestVersion: string | null
  seasonLengthDays: number; serverTime: Iso
}

export interface ThresholdDto { thresholdPct: number; previousPct: number; fieldsRezoned: number; zones: number }

export interface AttentionDto {
  priority: number; kind: string; tone: 'clay' | 'wheat' | 'forest' | 'slate'; title: string; detail: string
  link: string; fieldId: string | null; refId: string | null
}

export interface ActivityDto {
  id: string; kind: string; title: string; subtitle: string; at: Iso; fieldId: string | null
  tone: string; source: 'phone' | 'dashboard'; actor: string | null
}

export interface OverviewDto {
  stats: {
    fields: number; areaAcres: number; zonesOpen: number; zonesOpenSqm: number; scansAwaitingReview: number
    cellsAbstained: number; seasonDay: number; seasonLengthDays: number; thresholdPct: number; season: string
    worstControl: { fieldId: string; fieldName: string; letter: string; efficacyPct: number } | null
  }
  needsAttention: AttentionDto[]
  recentActivity: ActivityDto[]
  recentAudit: AuditDto[]
}

export interface SeasonCalendarDto {
  season: string; seasonLengthDays: number; startsOn: string | null; today: string
  fields: { fieldId: string; fieldName: string; season: SeasonDto | null; surveys: SurveyBriefDto[] }[]
}

export interface ReviewCellsDto {
  fieldId: string; fieldName: string; surveyId: string; gridSize: GridSize; thresholdPct: number; count: number
}
