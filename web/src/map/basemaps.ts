import L from 'leaflet'

/*
 * Free, key-less base maps (Google Maps needs billing). Satellite is Esri World Imagery, sharp enough over
 * Punjab to see killa lines and bunds; street is OpenStreetMap. Both only ask for the attribution shown.
 * shortcut: public tile servers, fine for a station's light use; for heavy use self-host tiles or swap these URLs.
 * Beyond their native zoom (19) they are scaled, so a drone orthomosaic can be inspected closer than the basemap.
 */
export const satellite = () => L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
  maxZoom: 23, maxNativeZoom: 19, attribution: 'Imagery © Esri, Maxar, Earthstar Geographics',
})
export const streets = () => L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 23, maxNativeZoom: 19, attribution: '© OpenStreetMap contributors',
})
