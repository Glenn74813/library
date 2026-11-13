import * as SecureStore from 'expo-secure-store'
import { API_URL } from './config'
import type { AuthResponse } from './types'

/**
 * Talks to the library server as the signed-in patron. The session (access and refresh tokens) is kept in
 * the phone's secure storage (iOS Keychain / Android Keystore), never in plain files.
 */
const KEY = 'smartlib.patronSession'

let session: AuthResponse | null = null
const listeners = new Set<() => void>()

export const getSession = () => session
export function subscribe(l: () => void) {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

async function setSession(s: AuthResponse | null) {
  session = s
  if (s) await SecureStore.setItemAsync(KEY, JSON.stringify(s))
  else await SecureStore.deleteItemAsync(KEY)
  listeners.forEach((l) => l())
}

/** Loads the saved session when the app starts. */
export async function restoreSession() {
  try {
    const raw = await SecureStore.getItemAsync(KEY)
    session = raw ? (JSON.parse(raw) as AuthResponse) : null
    // A refresh token that has run out can't be used: start signed out.
    if (session && Date.parse(session.refreshTokenExpiresAt) < Date.now()) await setSession(null)
  } catch {
    session = null
  }
  listeners.forEach((l) => l())
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export async function signIn(cardNumber: string, pin: string) {
  const res = await fetch(`${API_URL}/api/auth/patron-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cardNumber: cardNumber.trim(), pin }),
  }).catch(() => { throw new ApiError(0, 'Cannot reach the library. Check your internet connection.') })
  if (!res.ok) throw new ApiError(res.status, await problem(res, 'Wrong library ID or PIN'))
  const s = (await res.json()) as AuthResponse
  if (!s.user.roles.includes('PATRON')) throw new ApiError(403, 'This app is for library patrons. Staff use the library website.')
  await setSession(s)
}

export async function signOut() {
  const s = session
  await setSession(null)
  if (s) {
    // Tell the server so the refresh token can't be used again; signing out works offline too.
    fetch(`${API_URL}/api/auth/logout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: s.refreshToken }),
    }).catch(() => {})
  }
}

// One refresh at a time: the server treats a reused refresh token as stolen and ends the session.
let refreshing: Promise<boolean> | null = null

function refresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const current = session
      if (!current) return false
      try {
        const res = await fetch(`${API_URL}/api/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        })
        if (!res.ok) {
          await setSession(null)
          return false
        }
        await setSession((await res.json()) as AuthResponse)
        return true
      } catch {
        return false
      } finally {
        refreshing = null
      }
    })()
  }
  return refreshing
}

/** GET (or POST when a body is given) to the library API as the patron; JSON in, JSON out. */
export async function api<T = unknown>(path: string, opts: { method?: string; body?: unknown; anonymous?: boolean } = {}): Promise<T> {
  const send = (token?: string) => fetch(`${API_URL}${path}`, {
    method: opts.method ?? (opts.body !== undefined ? 'POST' : 'GET'),
    headers: {
      ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  })
  const token = opts.anonymous ? undefined : session?.accessToken
  let res: Response
  try {
    res = await send(token)
    if (res.status === 401 && token && (await refresh())) res = await send(session?.accessToken)
  } catch {
    throw new ApiError(0, 'Cannot reach the library. Check your internet connection.')
  }
  if (!res.ok) throw new ApiError(res.status, await problem(res, `Request failed (${res.status})`))
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** The server's error message (RFC 7807 problem details), or a fallback. */
async function problem(res: Response, fallback: string) {
  try {
    const p = await res.json()
    return p.detail || p.title || fallback
  } catch {
    return fallback
  }
}

/** Full address of a server path such as a photo URL ("/api/opac/photos/..."). */
export const absolute = (path: string) => (/^https?:/.test(path) ? path : `${API_URL}${path}`)

export const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e))
