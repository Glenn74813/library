import type { AuthResponse, CheckoutResult, Principal } from './types'

const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

/**
 * Independent session slots, so signing in to one never signs another out on the same browser:
 * "user" for staff, "patron" for the My Library portal, and "kiosk" for an unattended kiosk
 * signed in with a KIOSK device account.
 */
export type Slot = 'user' | 'patron' | 'kiosk'

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

const sessions: Record<Slot, Session | null> = { user: load('user'), patron: load('patron'), kiosk: load('kiosk') }

// Patrons used to share the staff slot: move such a session to the patron slot.
if (sessions.user?.user.roles.includes('PATRON')) {
  sessions.patron ??= sessions.user
  sessions.user = null
  try {
    localStorage.setItem(storageKey('patron'), JSON.stringify(sessions.patron))
    localStorage.removeItem(storageKey('user'))
  } catch { /* storage unavailable */ }
}

/**
 * The slot requests use when none is given: the patron session on My Library pages and in the
 * e-book reader (unless staff opened it with ?staff=1), the staff session everywhere else.
 */
export function defaultSlot(): Slot {
  const { pathname, search } = window.location
  if (pathname.startsWith('/my')) return 'patron'
  if (pathname.startsWith('/read')) return !search.includes('staff=1') && sessions.patron ? 'patron' : 'user'
  return 'user'
}
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

// Another tab signed in, out, or rotated the tokens: pick up its session so this tab never
// refreshes with an already-used refresh token (the server treats reuse as theft).
const SLOTS: Slot[] = ['user', 'patron', 'kiosk']
window.addEventListener('storage', (e) => {
  const slot = SLOTS.find((sl) => e.key === storageKey(sl))
  if (!slot && e.key !== null) return
  ;(slot ? [slot] : SLOTS).forEach((sl) => { sessions[sl] = load(sl) })
  listeners.forEach((l) => l())
})

// ------------------------------------------------------------------ kiosk device key

const DEVICE_KEY = 'smartlib.deviceKey'
let deviceKey: string | null = (() => { try { return localStorage.getItem(DEVICE_KEY) } catch { return null } })()

/**
 * The kiosk's device key (issued under Users & devices). Kiosk requests send it as X-Device-Key
 * instead of a signed-in session; it never expires until an administrator replaces or revokes it.
 */
export const getDeviceKey = () => deviceKey

export function setDeviceKey(key: string | null) {
  deviceKey = key?.trim() || null
  try {
    if (deviceKey) localStorage.setItem(DEVICE_KEY, deviceKey)
    else localStorage.removeItem(DEVICE_KEY)
  } catch {
    /* storage unavailable: the kiosk will ask again after a reload */
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

async function refresh(slot: Slot, usedToken?: string): Promise<boolean> {
  // Re-read storage: another tab may have rotated the tokens since this one loaded them.
  const stored = load(slot)
  if (stored?.refreshToken !== sessions[slot]?.refreshToken) {
    sessions[slot] = stored
    listeners.forEach((l) => l())
  }
  const current = sessions[slot]
  if (!current) return false
  // Already refreshed elsewhere: retry with that access token instead of rotating again.
  if (usedToken && current.accessToken !== usedToken && Date.parse(current.accessTokenExpiresAt) > Date.now()) return true
  if (!refreshing[slot]) {
    refreshing[slot] = (async () => {
      try {
        const res = await fetch(`${BASE}/api/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        })
        if (!res.ok) {
          // Only a refused token ends the session. A server or network error (502 while the API restarts,
          // 429) keeps it, so the desk stays signed in and can carry on in offline mode.
          if (res.status === 400 || res.status === 401 || res.status === 403) setSession(slot, null)
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
  /** Send this device key (kiosk setup checking a key before saving it) instead of the stored key or session. */
  deviceKey?: string
}

async function send(path: string, opts: RequestOptions, retry: boolean): Promise<Response> {
  const slot = opts.slot ?? defaultSlot()
  const headers: Record<string, string> = {}
  const isForm = opts.body instanceof FormData
  if (opts.body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  // A kiosk with a device key uses it instead of a session.
  const useKey = !opts.deviceKey && slot === 'kiosk' && !!deviceKey && !opts.anonymous
  const token = useKey || opts.deviceKey ? undefined : sessions[slot]?.accessToken
  if (opts.deviceKey) headers['X-Device-Key'] = opts.deviceKey
  else if (useKey) headers['X-Device-Key'] = deviceKey!
  else if (token && !opts.anonymous) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(`${BASE}${path}`, {
    method: opts.method ?? (opts.body !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.body === undefined ? undefined : isForm ? (opts.body as FormData) : JSON.stringify(opts.body),
    signal: opts.timeoutMs ? AbortSignal.timeout(opts.timeoutMs) : undefined,
  })
  if (res.status === 401 && retry && token && !opts.anonymous && (await refresh(slot, token))) {
    return send(path, opts, false)
  }
  // A replaced or revoked key never starts working again: forget it so the kiosk shows its setup screen.
  if (res.status === 401 && useKey) setDeviceKey(null)
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
export async function fetchBytes(path: string, slot: Slot = defaultSlot(), anonymous = false) {
  const res = await send(path, { slot, anonymous }, true)
  return { data: await res.arrayBuffer(), contentType: res.headers.get('Content-Type') ?? '' }
}

/** Downloads a file (CSV export etc.) using the current session. */
export async function download(path: string, fallbackName: string, slot: Slot = defaultSlot()) {
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

/** Opens a PDF (labels, cards) in a new tab for printing. The tab opens first so pop-up blockers allow it. */
export async function openPdf(path: string, slot: Slot = 'user') {
  const tab = window.open('', '_blank')
  try {
    const res = await send(path, { slot }, true)
    const url = URL.createObjectURL(await res.blob())
    if (tab) tab.location.href = url
    else window.location.href = url
  } catch (e) {
    tab?.close()
    throw e
  }
}

export async function login(username: string, password: string, slot: Slot = 'user') {
  const r = await api<AuthResponse>('/api/auth/login', { body: { username, password }, anonymous: true })
  setSession(slot, fromAuthResponse(r))
  return r.user
}

export async function patronLogin(cardNumber: string, pin: string) {
  const r = await api<AuthResponse>('/api/auth/patron-login', { body: { cardNumber, pin }, anonymous: true })
  setSession('patron', fromAuthResponse(r))
  return r.user
}

export async function logout(slot: Slot = defaultSlot()) {
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
