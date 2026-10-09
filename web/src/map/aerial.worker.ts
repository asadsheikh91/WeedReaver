import { finishImage, renderBand, specSize, type AerialSpec } from './aerialCore'

type Req =
  | { id: number; kind: 'band'; spec: AerialSpec; y0: number; y1: number }
  | { id: number; kind: 'finish'; spec: AerialSpec; base: ArrayBuffer }

interface WorkerScope {
  onmessage: ((e: MessageEvent<Req>) => void) | null
  postMessage(msg: unknown, transfer?: Transferable[]): void
}
const scope = self as unknown as WorkerScope

scope.onmessage = (e) => {
  const req = e.data
  try {
    if (req.kind === 'band') {
      const pixels = renderBand(req.spec, req.y0, req.y1)
      scope.postMessage({ id: req.id, kind: 'band', y0: req.y0, pixels }, [pixels.buffer])
    } else {
      const { w, h } = specSize(req.spec)
      const canvas = finishImage(req.spec, new Uint8ClampedArray(req.base))
      const bitmap = canvas.transferToImageBitmap()
      scope.postMessage({ id: req.id, kind: 'finish', bitmap, w, h }, [bitmap])
    }
  } catch (err) {
    scope.postMessage({ id: req.id, kind: 'error', message: String(err) })
  }
}
