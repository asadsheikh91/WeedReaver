import { area } from './geo'
import type { Pt } from './types'

/** Reads a polygon out of KML or GeoJSON and converts it to the local metric frame (metres). */
export function parseBoundary(text: string): { poly: Pt[]; lat: number; lon: number } | null {
  const pairs: [number, number][] = []
  const kml = /<coordinates>([\s\S]*?)<\/coordinates>/.exec(text)
  if (kml) {
    for (const t of kml[1].trim().split(/\s+/)) {
      const p = t.split(',')
      const lon = parseFloat(p[0]), lat = parseFloat(p[1])
      if (!Number.isNaN(lon) && !Number.isNaN(lat)) pairs.push([lon, lat])
    }
  } else {
    const re = /\[\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*]/g
    let m: RegExpExecArray | null
    while ((m = re.exec(text))) pairs.push([parseFloat(m[1]), parseFloat(m[2])])
  }
  if (pairs.length < 4) return null
  const lat0 = Math.max(...pairs.map((p) => p[1]))
  const lon0 = Math.min(...pairs.map((p) => p[0]))
  let poly = pairs.map(([lon, lat]) => ({
    x: (lon - lon0) * 111320 * Math.cos((lat0 * Math.PI) / 180),
    y: (lat0 - lat) * 111320,
  }))
  if (Math.hypot(poly[0].x - poly[poly.length - 1].x, poly[0].y - poly[poly.length - 1].y) < 0.5) poly = poly.slice(0, -1)
  const a = area(poly)
  if (a < 200 || a > 2_000_000) return null
  return { poly, lat: lat0, lon: lon0 }
}

/** A plausible plot next to Chak 47, so the import flow can be demonstrated without a file to hand. */
export function sampleKml(): string {
  const lon = 73.2741, lat = 31.8967
  const dx = 0.00124, dy = 0.00082
  const ring = [[lon, lat], [lon + dx, lat + 0.00004], [lon + dx + 0.00006, lat - dy], [lon + 0.00002, lat - dy - 0.00003], [lon, lat]]
  return `<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Station plot register</name>
<Placemark><name>Tubewell killa</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
${ring.map(([x, y]) => `${x.toFixed(6)},${y.toFixed(6)},0`).join('\n')}
</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>`
}
