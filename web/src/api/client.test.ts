import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// The client keeps its session in module state and reads `window`/`localStorage` at import, so each
// test stubs those browser globals and imports a fresh copy.
type Handler = (url: string, init: RequestInit) => Response
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const expired = () => json(401, { error: { code: 'token_expired', message: 'Access token expired' } })
const tokens = (n: number) => ({ accessToken: `access-${n}`, refreshToken: `refresh-${n}`, expiresIn: 900, user: { id: 'U-1', email: 'a@b.pk', name: 'Analyst', role: 'ANALYST' as const, roleLabel: 'Analyst' } })

let calls: { url: string; auth: string | null; body?: string }[]
let store: Map<string, string>

async function loadClient(handler: Handler) {
  calls = []
  store = new Map<string, string>()
  vi.stubGlobal('window', { location: { origin: 'http://localhost:5173' }, addEventListener: () => {} })
  vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k) })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, auth: (init.headers as Record<string, string>).Authorization ?? null, body: init.body as string | undefined })
    return handler(url, init)
  }))
  vi.resetModules()
  const client = await import('./client')
  client.auth.accept(tokens(1))
  return client
}

beforeEach(() => vi.unstubAllGlobals())
afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('sends the access token as a bearer header', async () => {
    const { api } = await loadClient(() => json(200, { ok: true }))
    await api.get('/fields')
    expect(calls[0].auth).toBe('Bearer access-1')
  })

  it('refreshes once on token_expired and replays the request with the new token', async () => {
    let fieldsCalls = 0
    const { api, auth } = await loadClient((url) => {
      if (url.endsWith('/auth/refresh')) return json(200, tokens(2))
      return ++fieldsCalls === 1 ? expired() : json(200, [{ id: 'F-047' }])
    })
    await expect(api.get('/fields')).resolves.toEqual([{ id: 'F-047' }])
    expect(calls.map((c) => [c.url.split('/api/v1')[1], c.auth])).toEqual([
      ['/fields', 'Bearer access-1'], ['/auth/refresh', null], ['/fields', 'Bearer access-2'],
    ])
    expect(auth.signedIn).toBe(true)
  })

  it('shares one refresh between concurrent requests that all expired', async () => {
    const seen = new Set<string>()
    const { api } = await loadClient((url, init) => {
      if (url.endsWith('/auth/refresh')) return json(200, tokens(2))
      const bearer = (init.headers as Record<string, string>).Authorization
      if (bearer === 'Bearer access-1' && !seen.has(url)) { seen.add(url); return expired() }
      return json(200, { url })
    })
    await Promise.all([api.get('/fields'), api.get('/surveys'), api.get('/scans')])
    expect(calls.filter((c) => c.url.endsWith('/auth/refresh'))).toHaveLength(1)
  })

  it('refreshes with the token another tab rotated, not the stale one it loaded with', async () => {
    const { api } = await loadClient((url) => url.endsWith('/auth/refresh') ? json(200, tokens(3)) : calls.length === 1 ? expired() : json(200, []))
    store.set('wr.session', JSON.stringify(tokens(2))) // the other tab refreshed first
    await api.get('/fields')
    expect(JSON.parse(calls.find((c) => c.url.endsWith('/auth/refresh'))!.body!)).toEqual({ refreshToken: 'refresh-2' })
  })

  it('ends the session when the password changed elsewhere', async () => {
    const { api, auth } = await loadClient(() => json(401, { error: { code: 'token_revoked', message: 'Password changed; sign in again' } }))
    await expect(api.get('/fields')).rejects.toMatchObject({ code: 'token_revoked' })
    expect(auth.signedIn).toBe(false)
  })

  it('ends the session when the refresh token was already used', async () => {
    const { api, auth, ApiError } = await loadClient((url) =>
      url.endsWith('/auth/refresh') ? json(401, { error: { code: 'refresh_token_reused', message: 'sign in again' } }) : expired())
    await expect(api.get('/fields')).rejects.toBeInstanceOf(ApiError)
    expect(auth.signedIn).toBe(false)
  })

  it('surfaces the backend error envelope, using the first validation message', async () => {
    const { api } = await loadClient(() => json(422, {
      error: { code: 'validation_error', message: 'Request validation failed', details: [{ loc: ['body', 'name'], msg: 'Value error, A boundary needs between 3 and 5000 vertices' }] },
    }))
    await expect(api.post('/fields', {})).rejects.toMatchObject({ status: 422, code: 'validation_error', message: 'A boundary needs between 3 and 5000 vertices' })
  })

  it('reports an unreachable server as a network error, not a crash', async () => {
    const { api } = await loadClient(() => { throw new TypeError('Failed to fetch') })
    await expect(api.get('/fields')).rejects.toMatchObject({ status: 0, code: 'network_error' })
  })

  it('collects every page of a paginated list', async () => {
    const { api } = await loadClient((url) => {
      const offset = Number(new URL(url).searchParams.get('offset'))
      return json(200, { items: offset === 0 ? [1, 2] : offset === 2 ? [3] : [], total: 3, limit: 2, offset })
    })
    await expect(api.all('/scans', {}, 2)).resolves.toEqual([1, 2, 3])
  })
})
