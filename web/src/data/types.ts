export interface Pt { x: number; y: number }

export type WeedClass = 'CROP' | 'GRASS' | 'BROADLEAF'
export type Severity = 'CLEAN' | 'MODERATE' | 'HEAVY'
export type SurveyRole = 'PRE' | 'PLUS_14D' | 'PLUS_28D'
export type SurveyStatus = 'SCHEDULED' | 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED'
export type GridSize = 1 | 2 | 5
export type ZoneState = 'FLAGGED' | 'ROUTED' | 'TREATED' | 'RESURVEYED'
export type HracGroup = 'G1' | 'G2' | 'G3' | 'G4' | 'G5' | 'G9' | 'G15'

export const WEED_CLASS = {
  CROP: { label: 'Crop canopy', short: 'Crop', hint: 'No action indicated' },
  GRASS: { label: 'Grass weed', short: 'Grass', hint: 'Points to ACCase (HRAC 1) or ALS (HRAC 2) chemistry' },
  BROADLEAF: { label: 'Broadleaf weed', short: 'Broadleaf', hint: 'Points to a different mode-of-action group' },
} as const

export const SEVERITY = {
  CLEAN: { label: 'Clean', meaning: 'No treatment indicated' },
  MODERATE: { label: 'Moderate', meaning: 'Spot treatment indicated' },
  HEAVY: { label: 'Heavy', meaning: 'Likely control failure — investigate' },
} as const

export const SURVEY_ROLE = {
  PRE: { label: 'Pre-treatment', short: 'Pre' },
  PLUS_14D: { label: 'Follow-up +14 d', short: '+14 d' },
  PLUS_28D: { label: 'Follow-up +28 d', short: '+28 d' },
} as const

export const SURVEY_STATUS: Record<SurveyStatus, string> = {
  SCHEDULED: 'Scheduled', QUEUED: 'Queued', PROCESSING: 'Processing', READY: 'Ready', FAILED: 'Failed',
}

export const GRID_ACTUATOR: Record<GridSize, string> = {
  1: 'Knapsack operator on a navigation prompt',
  2: 'Section control on a tractor boom',
  5: 'Robust to unassisted smartphone GNSS drift',
}

export const HRAC_MOA: Record<HracGroup, string> = {
  G1: 'ACCase inhibitor', G2: 'ALS inhibitor', G3: 'Microtubule inhibitor', G4: 'Auxin mimic',
  G5: 'PS II inhibitor', G9: 'EPSP synthase inhibitor', G15: 'VLCFA inhibitor',
}
export const hracCode = (g: HracGroup) => g.slice(1)
export const hracDisplay = (g: HracGroup) => `Group ${hracCode(g)} · ${HRAC_MOA[g]}`

export interface Landscape {
  seed: number
  canal?: Pt[]
  road?: Pt[]
  watercourse?: Pt[]
  farmstead?: Pt
  village?: Pt
  tubewell?: Pt
  mustardBias: number
}

export interface FieldParcel {
  id: string
  name: string
  village: string
  boundary: Pt[]
  lat: number
  lon: number
  capturedBy: string
  landscape: Landscape
  gate: Pt
  captureMethod: string
  /** Area as the server measured it; used in preference to recomputing from the boundary. */
  areaAcres?: number
  /** Weed patches drawn into the synthetic imagery. Derived from the published zones of a surveyed field. */
  patches?: Patch[]
}

export interface FieldSeason {
  id: string; fieldId: string; crop: string; season: string
  sowingDate: number; rowSpacingCm: number; variety: string; harvestDate: number | null
}

export interface Survey {
  id: string; fieldSeasonId: string; flownAt: number; role: SurveyRole
  altitudeM: number; sensor: string; gsdCm: number; status: SurveyStatus
  images: number; progress: number; source?: string; stage?: string
  fieldId?: string; error?: string
}

export interface GridCell {
  col: number; row: number; infestPct: number; weedClass: WeedClass
  confidence: number; abstained: boolean; treated: boolean; inside: boolean
}

export interface RasterGrid {
  cols: number; rows: number; cellMeters: GridSize
  originX: number; originY: number
  cells: GridCell[]
  total: number; flagged: number; abstained: number
  treatedFraction: number; treatedSqm: number
}

export interface TreatmentZone {
  id: string; label: string; letter: string; severity: Severity; dominantClass: WeedClass
  areaSqm: number; cellCount: number; cx: number; cy: number; radiusM: number
  distanceM: number; state: ZoneState; meanInfestPct: number
  efficacyPct?: number; treatedAt?: number
}

export interface Patch {
  cx: number; cy: number; radiusM: number; peak: number
  weedClass: WeedClass; label: string; stretch: number
}

export interface Product {
  trade: string; active: string; hrac: HracGroup; target: WeedClass
  crop: string; formulation: string; registered: boolean
}

export interface TreatmentRecord {
  id: string; fieldSeasonId: string; fieldId: string; fieldName: string
  zoneLabels: string[]; appliedAt: number; product: string; activeIngredient: string
  hracGroup: HracGroup; doseRecorded: string; doseUnit: string; applicationMode: string
  growthStage: string; operator: string; areaAcres: number; synced: boolean
  waterLitres: string; notes: string
}

export interface Species {
  latin: string; local: string; common: string; cls: WeedClass; note: string
}

export interface LeafScan {
  id: string; at: number; speciesLatin: string; speciesLocal: string
  weedClass: WeedClass; confidence: number; abstained: boolean
  fieldId: string; fieldName: string; zoneLabel?: string; frameId?: string
  lat: number | null; lon: number | null; gnssAccuracyM: number; modelVersion: string
  synced: boolean; leafSeed: number; runnerUp: [string, number][]; inferenceMs: number
  resolved: boolean; annotation?: string; resolvedBy?: string; resolvedAt?: number; note?: string
  deviceId: string; deviceName?: string; hasPhoto?: boolean
  /** The operator's suggested label, sent from the field before an analyst resolves the scan. */
  suggestion?: string
}

export interface QuadratRecord {
  id: string; frameId: string; recordedAt: number; fieldName: string; zoneLabel: string
  speciesCounts: [string, number][]; verifiedBy: string; seed: number
}

export interface ChangeLogEntry {
  id: string; seq: number; entity: string; summary: string; at: number
  ownedByMobile: boolean; bytes: number; deviceId: string; applied: boolean
  deviceName?: string; status?: 'applied' | 'duplicate' | 'rejected'; reason?: string
}

export interface Device {
  id: string; name: string; model: string; os: string; operator: string; operatorId: string
  appVersion: string; battery: number; lastSeen: number | null; lastSync: number | null; online: boolean
  storageMb: number; pendingChanges: number; revoked: boolean; updateAvailable: boolean
}

export interface UserAccount {
  id: string; name: string; role: 'Analyst' | 'Field operator' | 'Trainee' | 'Administrator'
  email: string; lastActive: number; scope: string
}

export interface ExportJob {
  id: string; fieldId: string; format: 'GeoJSON' | 'Shapefile' | 'TASKDATA'
  at: number; sizeKb: number; cells: number; zones: number; threshold: number; gridSize: GridSize
  filename: string
  downloadUrl?: string
}

export interface AuditEntry {
  id: string; at: number; who: string; action: string; detail: string
}
