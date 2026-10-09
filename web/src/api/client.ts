/**
 * The one way the dashboard talks to the WeedReaver API.
 *
 * Bearer auth with the access token; when the API answers `token_expired` the refresh token is rotated once
 * (concurrent requests share the same refresh) and the request is replayed. A refresh that fails ends the
 * session. Every non-2xx response is turned into an ApiError carrying the backend's error envelope.
 */

export const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/+$/, '') || 'http://localhost:8000/api/v1'

const STORAGE_KEY = 'wr.session'

export interface SessionUser {
  id: string; email: string; name: string; role: 'ADMIN' | 'ANALYST' | 'OPERATOR' | 'TRAINEE'; roleLabel: string
}
interface Session { accessToken: string; refreshToken: string; user: SessionUser }
export interface TokenOut { accessToken: string; refreshToken: string; expiresIn: number; user: SessionUser }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly details?: unknown) {
    super(message)
  }
}

/** Codes that mean the session is over and the user has to sign in again. */
const SESSION_ENDED = new Set(['refresh_token_reused', 'refresh_token_expired', 'account_disabled', 'unauthorized', 'invalid_token'])

let session: Session | null = read()
const listeners = new Set<() => void>()

function read(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}
function write(s: Session | null) {
  session = s
  try {
    if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s))
    else localStorage.removeItem(STORAGE_KEY)
  } catch { /* private mode: the session lives for this tab only */ }
  listeners.forEach((l) => l())
}

export const auth = {
  get user() { return session?.user ?? null },
  get signedIn() { return !!session },
  subscribe(fn: () => void) { listeners.add(fn); return () => { listeners.delete(fn) } },
  /** Store the tokens returned by login, refresh or accept-invite. */
  accept(t: TokenOut) { write({ accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user }) },
  async signIn(email: string, password: string) {
    const t = await request<TokenOut>('POST', '/auth/login', { body: { email, password }, anonymous: true })
    auth.accept(t)
    return t.user
  },
  async signOut() {
    const s = session
    write(null)
    if (s) await request('POST', '/auth/logout', { body: { refreshToken: s.refreshToken }, anonymous: true }).catch(() => {})
  },
  /** Drop the session locally without telling the server (it already refused the tokens). */
  expire() { write(null) },
}

let refreshing: Promise<boolean> | null = null
function refresh(): Promise<boolean> {
  if (!refreshing) {
    const token = session?.refreshToken
    refreshing = (async () => {
      if (!token) return false
      try {
        const t = await request<TokenOut>('POST', '/auth/refresh', { body: { refreshToken: token }, anonymous: true })
        auth.accept(t)
        return true
      } catch (e) {
        if (e instanceof ApiError && (e.status === 401 || SESSION_ENDED.has(e.code))) auth.expire()
        return false
      }
    })().finally(() => { refreshing = null })
  }
  return refreshing
}

type Query = Record<string, string | number | boolean | null | undefined | (string | number)[]>

interface Options {
  query?: Query
  body?: unknown
  form?: FormData
  anonymous?: boolean
  raw?: boolean
  signal?: AbortSignal
  onUploadProgress?: (fraction: number) => void
}

export function url(path: string, query?: Query): string {
  const u = new URL(API_BASE + path, window.location.origin)
  if (query) for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v)) v.forEach((x) => u.searchParams.append(k, String(x)))
    else u.searchParams.set(k, String(v))
  }
  return u.toString()
}

async function toError(res: Response): Promise<ApiError> {
  let code = `http_${res.status}`, message = res.statusText || 'Request failed', details: unknown
  try {
    const j = await res.json()
    if (j?.error) { code = j.error.code ?? code; message = j.error.message ?? message; details = j.error.details }
  } catch { /* not JSON */ }
  if (code === 'validation_error' && Array.isArray(details) && details.length) {
    const first = details[0] as { msg?: string; message?: string }
    message = (first.msg ?? first.message ?? message).replace(/^Value error, /, '')
  }
  return new ApiError(res.status, code, message, details)
}

async function send(method: string, path: string, o: Options): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (!o.anonymous && session) headers.Authorization = `Bearer ${session.accessToken}`
  let body: BodyInit | undefined
  if (o.form) body = o.form
  else if (o.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(o.body) }
  if (o.onUploadProgress && o.form) return xhr(method, url(path, o.query), headers, o.form, o.onUploadProgress, o.signal)
  try {
    return await fetch(url(path, o.query), { method, headers, body, signal: o.signal })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError(0, 'network_error', `Cannot reach the WeedReaver server at ${API_BASE}`)
  }
}

/** fetch() cannot report upload progress, so multipart uploads that want it go through XHR. */
function xhr(method: string, to: string, headers: Record<string, string>, form: FormData, progress: (f: number) => void, signal?: AbortSignal): Promise<Response> {
  return new Promise((resolve, reject) => {
    const r = new XMLHttpRequest()
    r.open(method, to)
    for (const [k, v] of Object.entries(headers)) r.setRequestHeader(k, v)
    r.upload.onprogress = (e) => { if (e.lengthComputable) progress(e.loaded / e.total) }
    r.onload = () => resolve(new Response(r.responseText, { status: r.status, statusText: r.statusText, headers: { 'Content-Type': r.getResponseHeader('Content-Type') ?? 'application/json' } }))
    r.onerror = () => reject(new ApiError(0, 'network_error', `Cannot reach the WeedReaver server at ${API_BASE}`))
    signal?.addEventListener('abort', () => { r.abort(); reject(new DOMException('Aborted', 'AbortError')) })
    r.send(form)
  })
}

export async function request<T = unknown>(method: string, path: string, o: Options = {}): Promise<T> {
  let res = await send(method, path, o)
  if (res.status === 401 && !o.anonymous) {
    const err = await toError(res)
    if (err.code === 'token_expired' && (await refresh())) res = await send(method, path, o)
    else {
      if (SESSION_ENDED.has(err.code) || err.code === 'token_expired') auth.expire()
      throw err
    }
  }
  if (!res.ok) throw await toError(res)
  if (o.raw) return res as unknown as T
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const api = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown, query?: Query) => request<T>('POST', path, { body, query }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body }),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, { body }),
  del: <T>(path: string) => request<T>('DELETE', path),
  upload: <T>(path: string, form: FormData, onUploadProgress?: (f: number) => void) => request<T>('POST', path, { form, onUploadProgress }),
  /** Every page of a paginated list ({items, total, limit, offset}). */
  async all<T>(path: string, query: Query = {}, pageSize = 500): Promise<T[]> {
    const out: T[] = []
    for (let offset = 0; ; offset += pageSize) {
      const page = await request<{ items: T[]; total: number }>('GET', path, { query: { ...query, limit: pageSize, offset } })
      out.push(...page.items)
      if (out.length >= page.total || page.items.length === 0) return out
    }
  },
  /** Fetch an authenticated file and hand it to the browser as a download. */
  async download(path: string, fallbackName: string) {
    const res = await request<Response>('GET', path.startsWith(API_PATH_PREFIX) ? path.slice(API_PATH_PREFIX.length) : path, { raw: true })
    const name = /filename\*=UTF-8''([^;]+)/i.exec(res.headers.get('Content-Disposition') ?? '')?.[1]
    const blob = await res.blob()
    const href = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = href; a.download = name ? decodeURIComponent(name) : fallbackName
    document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(href), 1000)
    return blob.size
  },
  /** An authenticated binary (e.g. a scan photo) as an object URL. */
  async blobUrl(path: string) {
    const res = await request<Response>('GET', path, { raw: true })
    return URL.createObjectURL(await res.blob())
  },
}

/** The path part of API_BASE ("/api/v1"), so server-provided URLs like downloadUrl can be reused. */
const API_PATH_PREFIX = new URL(API_BASE, window.location.origin).pathname.replace(/\/+$/, '')

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : String(e))
