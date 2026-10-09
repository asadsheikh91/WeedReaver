import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { Crosshair, Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Button, TextInput } from '../ui/kit'
import './map.css'

export interface LatLon { lat: number; lon: number }

/*
 * Free, key-less base maps (Google Maps needs billing). Satellite is Esri World Imagery, sharp enough over
 * Punjab to see killa lines and bunds; street is OpenStreetMap. Both only ask for the attribution shown.
 * shortcut: public tile servers, fine for a station's light use; for heavy use self-host tiles or swap these URLs.
 */
const satellite = () => L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
  maxZoom: 20, maxNativeZoom: 19, attribution: 'Imagery © Esri, Maxar, Earthstar Geographics',
})
const streets = () => L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 20, maxNativeZoom: 19, attribution: '© OpenStreetMap contributors',
})
const CORNER = L.divIcon({ className: 'draw-corner', iconSize: [14, 14] })

/** Click the field's corners on the imagery; drag a corner to adjust it. */
export function DrawFieldMap({ ring, onChange, existing, center }: {
  ring: LatLon[]
  onChange: (ring: LatLon[]) => void
  existing: { name: string; ring: LatLon[] }[]
  center: LatLon
}) {
  const el = useRef<HTMLDivElement>(null)
  const map = useRef<L.Map | null>(null)
  const drawn = useRef<L.LayerGroup | null>(null)
  const latest = useRef({ ring, onChange })
  latest.current = { ring, onChange }
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [notFound, setNotFound] = useState(false)

  useEffect(() => {
    const sat = satellite()
    const m = L.map(el.current!, { center: [center.lat, center.lon], zoom: 16, layers: [sat] })
    L.control.layers({ Satellite: sat, Street: streets() }).addTo(m)
    existing.forEach((f) => L.polygon(f.ring.map((p): L.LatLngTuple => [p.lat, p.lon]), { color: '#FFF6DE', weight: 1.5, dashArray: '4 4', fillOpacity: 0.08, interactive: false })
      .bindTooltip(f.name, { permanent: true, direction: 'center', className: 'draw-label' }).addTo(m))
    m.on('click', (e: L.LeafletMouseEvent) => latest.current.onChange([...latest.current.ring, { lat: e.latlng.lat, lon: e.latlng.lng }]))
    drawn.current = L.layerGroup().addTo(m)
    map.current = m
    return () => { m.remove(); map.current = null }
  }, []) // built once; the outline is synced by the effect below

  useEffect(() => {
    const g = drawn.current
    if (!g) return
    g.clearLayers()
    const pts = ring.map((p) => [p.lat, p.lon] as [number, number])
    if (pts.length >= 2) L.polygon(pts, { color: '#C9983D', weight: 2.5, fillColor: '#C9983D', fillOpacity: 0.18, interactive: false }).addTo(g)
    ring.forEach((_, i) => {
      L.marker(pts[i], { icon: CORNER, draggable: true, keyboard: false })
        .on('dragend', (e) => {
          const ll = (e.target as L.Marker).getLatLng()
          latest.current.onChange(latest.current.ring.map((p, j) => (j === i ? { lat: ll.lat, lon: ll.lng } : p)))
        })
        .addTo(g)
    })
  }, [ring])

  // Nominatim: OpenStreetMap's free place search (no key; one request per search, as its policy asks).
  const search = async () => {
    if (!query.trim()) return
    setSearching(true); setNotFound(false)
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=pk&q=${encodeURIComponent(query.trim())}`)
      const [hit] = (await r.json()) as { lat: string; lon: string }[]
      if (hit) map.current?.setView([Number(hit.lat), Number(hit.lon)], 16)
      else setNotFound(true)
    } catch { setNotFound(true) }
    setSearching(false)
  }

  return (
    <div className="col gap-8">
      <div className="row gap-8">
        <div className="grow">
          <TextInput placeholder="Search a village or place, e.g. Pindi Bhattian" value={query} aria-label="Search a place"
            onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void search() } }} />
        </div>
        <Button icon={Search} loading={searching} onClick={() => void search()}>Find</Button>
        <Button variant="ghost" icon={Crosshair} onClick={() => map.current?.locate({ setView: true, maxZoom: 17 })}>My location</Button>
      </div>
      {notFound && <span className="caption">No place found. Try the village or tehsil name.</span>}
      <div ref={el} className="draw-map" />
    </div>
  )
}
