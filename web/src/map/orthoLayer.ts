import L from 'leaflet'
import { request } from '../api/client'
import type { OrthomosaicDto } from '../api/dto'

/*
 * A flight's orthomosaic on a Leaflet map. Tiles come from the API, which needs the bearer token, so they
 * cannot be plain <img src> URLs: each tile is fetched through the API client (which refreshes an expired
 * token once and replays) and shown from an object URL. Tiles scrolled away mid-flight are aborted.
 *
 * The layer only asks for tiles inside the mosaic's bounds, and draws its native zoom scaled beyond that.
 */

const EMPTY = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
const controllers = new WeakMap<HTMLElement, AbortController>()

interface OrthoOptions extends L.GridLayerOptions { tileUrl: string }

const OrthoGridLayer = L.GridLayer.extend({
  createTile(this: L.GridLayer & { options: OrthoOptions }, coords: L.Coords, done: L.DoneCallback): HTMLElement {
    const img = document.createElement('img')
    img.alt = ''
    img.setAttribute('role', 'presentation')
    img.decoding = 'async'
    const ctrl = new AbortController()
    controllers.set(img, ctrl)
    const path = this.options.tileUrl.replace('{z}', String(coords.z)).replace('{x}', String(coords.x)).replace('{y}', String(coords.y))
    request<Response>('GET', path, { raw: true, signal: ctrl.signal })
      .then(async (res) => {
        if (res.status === 204) { img.src = EMPTY; done(undefined, img); return }
        const url = URL.createObjectURL(await res.blob())
        img.onload = () => { URL.revokeObjectURL(url); done(undefined, img) }
        img.onerror = () => { URL.revokeObjectURL(url); img.src = EMPTY; done(new Error('tile could not be decoded'), img) }
        img.src = url
      })
      .catch((e: unknown) => {
        if ((e as Error).name === 'AbortError') return
        img.src = EMPTY
        done(e as Error, img)
      })
    return img
  },
}) as unknown as new (o: OrthoOptions) => L.GridLayer

export function orthoLayer(info: OrthomosaicDto, opacity = 1): L.GridLayer {
  const b = info.bounds
  const layer = new OrthoGridLayer({
    tileUrl: info.tileUrl,
    tileSize: 256,
    bounds: L.latLngBounds([b.south, b.west], [b.north, b.east]),
    // Below this the whole mosaic is a few pixels wide; the field's pin says more.
    minZoom: Math.max(0, info.minZoom - 5),
    maxNativeZoom: info.maxZoom,
    maxZoom: 24,
    opacity,
    keepBuffer: 2,
    updateWhenZooming: false,
    className: 'ortho-layer',
    zIndex: 5,
  })
  layer.on('tileunload', (e: L.TileEvent) => { controllers.get(e.tile)?.abort(); controllers.delete(e.tile) })
  return layer
}

export const orthoBounds = (info: OrthomosaicDto) =>
  L.latLngBounds([info.bounds.south, info.bounds.west], [info.bounds.north, info.bounds.east])
