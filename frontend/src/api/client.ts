import type { AuthResponse, CheckoutResult, Principal } from './types'

const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

/**
 * Two independent session slots: "user" for staff/patrons in a normal browser, "kiosk" for an
 * unattended OPAC/attendance kiosk signed in with a KIOSK account (so a librarian can also log in
 * on the same machine without logging the kiosk out).
 */
export type Slot = 'user' | 'kiosk'

export interface Session {
  accessToken: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  user: Principal
}

const storageKey = (slot: Slot) => `smartlib.session.${slot}`

function load(slot: Slot): Session | null {
  try {
    const raw = localStorage.getItem(storageKey(slot))
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

const sessions: Record<Slot, Session | null> = { user: load('user'), kiosk: load('kiosk') }
const listeners = new Set<() => void>()

export function getSession(slot: Slot = 'user'): Session | null {
  return sessions[slot]
}

export function setSession(slot: Slot, s: Session | null) {
  sessions[slot] = s
  try {
    if (s) localStorage.setItem(storageKey(slot), JSON.stringify(s))
    else localStorage.removeItem(storageKey(slot))
  } catch {
    /* storage unavailable (private mode): session lives in memory only */
  }
  listeners.forEach((l) => l())
}

export function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function fromAuthResponse(r: AuthResponse): Session {
  return { ...r }
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

// Single-flight refresh per slot so parallel 401s trigger only one rotation.
const refreshing: Partial<Record<Slot, Promise<boolean>>> = {}

async function refresh(slot: Slot): Promise<boolean> {
  const current = sessions[slot]
  if (!current) return false
  if (!refreshing[slot]) {
    refreshing[slot] = (async () => {
      try {
        const res = await fetch(`${BASE}/api/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        })
        if (!res.ok) {
          setSession(slot, null)
          return false
        }
        setSession(slot, fromAuthResponse(await res.json()))
        return true
      } catch {
        return false
      } finally {
        delete refreshing[slot]
      }
    })()
  }
  return refreshing[slot]!
}

export interface RequestOptions {
  method?: string
  body?: unknown
  slot?: Slot
  /** Send without Authorization header (public endpoints). */
  anonymous?: boolean
  /** Give up after this long (treated like a network failure), e.g. so the desk can switch to offline mode. */
  timeoutMs?: number
}

async function send(path: string, opts: RequestOptions, retry: boolean): Promise<Response> {
  const slot = opts.slot ?? 'user'
  const headers: Record<string, string> = {}
  const isForm = opts.body instanceof FormData
  if (opts.body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  const token = sessions[slot]?.accessToken
  if (token && !opts.anonymous) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(`${BASE}${path}`, {
    method: opts.method ?? (opts.body !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.body === undefined ? undefined : isForm ? (opts.body as FormData) : JSON.stringify(opts.body),
    signal: opts.timeoutMs ? AbortSignal.timeout(opts.timeoutMs) : undefined,
  })
  if (res.status === 401 && retry && token && !opts.anonymous && (await refresh(slot))) {
    return send(path, opts, false)
  }
  if (!res.ok) {
    let message = res.statusText || `Request failed (${res.status})`
    try {
      const problem = await res.json()
      message = problem.detail || problem.title || message
    } catch {
      /* not JSON */
    }
    if (res.status === 403 && message === 'Forbidden') message = 'You do not have permission to do that'
    throw new ApiError(res.status, message)
  }
  return res
}

export async function api<T = void>(path: string, opts: RequestOptions = {}): Promise<T> {
  const res = await send(path, opts, true)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** Fetches a protected file into memory (e-book reader); nothing is saved to disk. */
export async function fetchBytes(path: string, slot: Slot = 'user', anonymous = false) {
  const res = await send(path, { slot, anonymous }, true)
  return { data: await res.arrayBuffer(), contentType: res.headers.get('Content-Type') ?? '' }
}

/** Downloads a file (CSV export etc.) using the current session. */
export async function download(path: string, fallbackName: string, slot: Slot = 'user') {
  const res = await send(path, { slot }, true)
  const blob = await res.blob()
  const cd = res.headers.get('Content-Disposition') ?? ''
  const name = /filename="([^"]+)"/.exec(cd)?.[1] ?? fallbackName
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export async function login(username: string, password: string, slot: Slot = 'user') {
  const r = await api<AuthResponse>('/api/auth/login', { body: { username, password }, anonymous: true })
  setSession(slot, fromAuthResponse(r))
  return r.user
}

export async function patronLogin(cardNumber: string, pin: string) {
  const r = await api<AuthResponse>('/api/auth/patron-login', { body: { cardNumber, pin }, anonymous: true })
  setSession('user', fromAuthResponse(r))
  return r.user
}

export async function logout(slot: Slot = 'user') {
  const s = sessions[slot]
  setSession(slot, null)
  if (s) {
    try {
      await api('/api/auth/logout', { body: { refreshToken: s.refreshToken }, anonymous: true })
    } catch {
      /* already invalid */
    }
  }
}

export function qs(params: Record<string, string | number | boolean | undefined | null>) {
  const sp = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v))
  })
  const s = sp.toString()
  return s ? `?${s}` : ''
}

/** Hands a ready-for-pickup reserved copy to its patron (checks it out; the hold becomes fulfilled). */
export function releaseHold(h: { cardNumber: string; itemBarcode: string | null }) {
  if (!h.itemBarcode) return Promise.reject(new Error('No copy has been set aside for this hold yet'))
  return api<CheckoutResult>('/api/circulation/checkout', { body: { patron: h.cardNumber, item: h.itemBarcode } })
}
