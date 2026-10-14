import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Drives the store's flight upload against a scripted API: fetch for JSON, XMLHttpRequest for the multipart
// batches (the only way to report upload progress). Browser globals are stubbed and modules reloaded per test.
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const survey = (over: Record<string, unknown> = {}) => ({
  id: 'S-9', fieldSeasonId: 'FS-9', fieldId: 'F-9', fieldName: 'Test', role: 'PRE', status: 'SCHEDULED', flownAt: '2027-01-22T04:00:00Z',
  altitudeM: 15, sensor: 'DJI', gsdCm: 0, images: 0, imageBytes: 0, progress: 0, stage: null, source: null, error: null,
  pipeline: null, modelVersion: null, processedAt: null, hasOrthomosaic: false, job: null, ...over,
})

let fetched: { method: string; path: string; body?: string }[]
let sent: { names: string[] }[]
let xhrStatuses: number[]
let processAnswer: Response

class FakeXHR {
  status = 0; statusText = ''; responseText = ''
  upload = { onprogress: null as null | ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) }
  onload: null | (() => void) = null
  onerror: null | (() => void) = null
  open() {}
  setRequestHeader() {}
  getResponseHeader() { return 'application/json' }
  abort() {}
  send(form: FormData) {
    const names = (form.getAll('files') as File[]).map((f) => f.name)
    sent.push({ names })
    const status = xhrStatuses.shift() ?? 200
    queueMicrotask(() => {
      this.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 1 })
      if (status === 0) { this.onerror?.(); return }
      this.status = status
      this.responseText = JSON.stringify(status === 200
        ? { surveyId: 'S-9', received: names.length, duplicates: 0, rejected: [], images: 2, imageBytes: 7, withGps: 2 }
        : { error: { code: 'http_error', message: 'Bad gateway' } })
      this.onload?.()
    })
  }
}

async function loadStore() {
  fetched = []; sent = []
  const ls = new Map<string, string>([['wr.session', JSON.stringify({ accessToken: 'a', refreshToken: 'r', user: { id: 'U-1', role: 'ANALYST' } })]])
  vi.stubGlobal('window', { location: { origin: 'http://localhost:5173' }, addEventListener: () => {} })
  vi.stubGlobal('localStorage', { getItem: (k: string) => ls.get(k) ?? null, setItem: (k: string, v: string) => void ls.set(k, v), removeItem: (k: string) => void ls.delete(k) })
  vi.stubGlobal('XMLHttpRequest', FakeXHR)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    const path = url.split('/api/v1')[1].split('?')[0]
    fetched.push({ method: init.method ?? 'GET', path, body: init.body as string | undefined })
    if (path === '/surveys/uploads') return json(201, survey())
    if (path === '/surveys/S-9/images') return json(200, { surveyId: 'S-9', images: 1, imageBytes: 3, withGps: 1, gpsCentre: null, offsetFromFieldM: 0, files: [{ filename: 'DJI_0001.JPG', sizeBytes: 3, hasGps: true }] })
    if (path === '/surveys/S-9/process') return processAnswer
    if (path === '/surveys/S-9') return json(200, survey({ status: 'QUEUED', images: 2 }))
    return json(200, { items: [], total: 0 })
  }))
  vi.resetModules()
  return import('./store')
}

beforeEach(() => { vi.unstubAllGlobals(); vi.useFakeTimers(); xhrStatuses = []; processAnswer = json(202, { id: 'job' }) })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

const files = () => [new File(['abc'], 'DJI_0001.JPG'), new File(['abcd'], 'DJI 0002.JPG')]

describe('flight upload', () => {
  it('skips files the station already holds, retries a failed batch, then queues processing', async () => {
    const { useStore } = await loadStore()
    xhrStatuses = [502, 200]
    const progress: number[] = []
    const run = useStore.getState().uploadFlight({ fieldId: 'F-9', role: 'PRE', files: files(), name: 'card', onProgress: (f) => progress.push(f) })
    await vi.advanceTimersByTimeAsync(1500) // the retry waits a second
    await expect(run).resolves.toBe('S-9')
    // DJI_0001.JPG (3 bytes) was already uploaded; "DJI 0002.JPG" is stored as DJI_0002.JPG and is new.
    expect(sent).toEqual([{ names: ['DJI 0002.JPG'] }, { names: ['DJI 0002.JPG'] }])
    expect(progress[0]).toBeCloseTo(3 / 7)
    expect(progress.at(-1)).toBe(1)
    expect(fetched.find((f) => f.path === '/surveys/S-9/process')?.body).toBe(JSON.stringify({ source: 'card' }))
    expect(useStore.getState().surveys.find((s) => s.id === 'S-9')?.status).toBe('QUEUED')
  })

  it('gives up after three retries on a server that keeps failing', async () => {
    const { useStore } = await loadStore()
    xhrStatuses = [503, 503, 503, 503]
    const run = useStore.getState().uploadFlight({ fieldId: 'F-9', role: 'PRE', files: files(), name: 'card' })
    const settled = run.catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(1000 + 3000 + 9000 + 100)
    expect(await settled).toMatchObject({ status: 503 })
    expect(sent).toHaveLength(4)
    expect(fetched.some((f) => f.path === '/surveys/S-9/process')).toBe(false)
  })

  it('does not retry a refusal, and reports a refused start with the flight it belongs to', async () => {
    const { useStore, FlightRefused } = await loadStore()
    processAnswer = json(422, { error: { code: 'invalid', message: '5 of 8 images carry no GPS position' } })
    const err = await useStore.getState().uploadFlight({ fieldId: 'F-9', role: 'PRE', files: files(), name: 'card' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(FlightRefused)
    expect(err).toMatchObject({ surveyId: 'S-9', message: '5 of 8 images carry no GPS position' })
  })
})

describe('storedName', () => {
  it('mirrors the server: unsafe characters become underscores, dots and underscores trimmed', async () => {
    const { storedName } = await loadStore()
    expect(storedName('DJI 0002.JPG')).toBe('DJI_0002.JPG')
    expect(storedName('_.photo (1).jpg._')).toBe('photo_1_.jpg')
    expect(storedName('ünïcode.JPG')).toBe('n_code.JPG')
    expect(storedName('..')).toBe('file')
  })
})
