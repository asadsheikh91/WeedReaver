import type { FieldParcel, Patch } from './types'

/**
 * The worked example on the public project page (/). It illustrates the method on a fixed copy of Chak 47
 * and never changes; everything under /app reads live data from the station server instead.
 */

const P = (cx: number, cy: number, radiusM: number, peak: number, weedClass: Patch['weedClass'], label: string, stretch = 1.35): Patch =>
  ({ cx, cy, radiusM, peak, weedClass, label, stretch })

export const SHOWCASE_FIELD: FieldParcel = {
  id: 'F-047', name: 'Chak 47', village: 'Pindi Bhattian, Hafizabad',
  boundary: [{ x: 22, y: 0 }, { x: 243, y: 0 }, { x: 245, y: 142 }, { x: 1, y: 143 }, { x: 0, y: 24 }, { x: 22, y: 24 }],
  lat: 31.8942, lon: 73.2711, capturedBy: 'Asad Mehmood', captureMethod: 'Surveyed', gate: { x: 1, y: 84 },
  landscape: {
    seed: 4711, mustardBias: 0.16,
    road: [{ x: -11, y: -260 }, { x: -12, y: 60 }, { x: -9, y: 420 }],
    watercourse: [{ x: -200, y: 148 }, { x: 120, y: 149 }, { x: 460, y: 151 }],
    farmstead: { x: -58, y: -44 }, tubewell: { x: 11, y: 12 },
  },
  patches: [
    P(60, 44, 22, 0.86, 'GRASS', 'A', 1.45), P(152, 50, 17, 0.62, 'GRASS', 'B', 1.5),
    P(204, 106, 19, 0.72, 'BROADLEAF', 'C', 1.1), P(100, 108, 15, 0.46, 'BROADLEAF', 'D', 1.15),
    P(28, 100, 11, 0.36, 'GRASS', 'E', 1.6), P(126, 14, 9, 0.22, 'GRASS', 'F', 1.8),
  ],
}

/** +14 d follow-up: fraction of each zone's peak that remains. Zone C barely moved: the resistance signal. */
export const SHOWCASE_EFFICACY: Record<string, number> = { A: 0.18, B: 0.34, C: 0.86, D: 0.26, E: 0.3 }

export const SHOWCASE_ROTATION = [
  { season: '2023-24', controlPct: 81 },
  { season: '2024-25', controlPct: 64 },
  { season: '2025-26', controlPct: 47 },
]
