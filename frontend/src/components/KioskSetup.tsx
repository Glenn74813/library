import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { AuthResponse } from '../api/types'
import { api, ApiError, fromAuthResponse, logout, setDeviceKey, setSession } from '../api/client'
import { ErrorAlert, Field, PasswordInput } from './ui'
import { errorText } from './Toast'
import { Logo } from './Logo'

/** Hold the top-left corner of a kiosk screen this long to disconnect it (staff only). */
const RESET_HOLD_MS = 5000

/**
 * One-time setup of a kiosk PC (attendance or self-checkout). The usual way is the device key
 * issued under Users & devices, pasted here or opened as a setup link (…#key=…). Signing in with a
 * kiosk account still works for kiosks set up that way before.
 */
export function KioskSetup({ title }: { title: string }) {
  const [key, setKey] = useState('')
  const [useAccount, setUseAccount] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function connect(candidate: string) {
    const k = candidate.trim()
    if (!k) return
    setBusy(true)
    setError(null)
    try {
      // Check the key before saving it: saving switches this screen to the kiosk itself.
      await api('/api/kiosk/config', { slot: 'kiosk', deviceKey: k, timeoutMs: 10_000 })
      setDeviceKey(k)
    } catch (err) {
      // Keep the key in the box so a network blip doesn't mean pasting it again.
      setKey(k)
      setError(err instanceof ApiError && err.status === 401
        ? 'This key was not accepted. Check that it was copied completely and that it is the device\'s current key.'
        : !(err instanceof ApiError) || err.status >= 500 ? 'Can\'t reach the server, try again.' : errorText(err))
    } finally {
      setBusy(false)
    }
  }

  // Setup link: /kiosk/attendance#key=kd_… (older links: ?key=…) uses the key and removes it from the address bar.
  // The fragment is never sent to the server, so the key stays out of access logs.
  const handledLink = useRef(false)
  useEffect(() => {
    if (handledLink.current) return
    handledLink.current = true
    const hash = new URLSearchParams(window.location.hash.replace(/^#/, ''))
    const params = new URLSearchParams(window.location.search)
    const fromLink = hash.get('key') ?? params.get('key')
    if (!fromLink) return
    hash.delete('key')
    params.delete('key')
    const rest = params.toString(), restHash = hash.toString()
    window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : '') + (restHash ? `#${restHash}` : ''))
    void connect(fromLink)
  }, [])

  async function signIn(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      // Check the role before keeping the session: an unattended kiosk must not hold a staff account.
      const r = await api<AuthResponse>('/api/auth/login', { body: { username, password }, anonymous: true })
      if (!r.user.roles.includes('KIOSK')) {
        await api('/api/auth/logout', { body: { refreshToken: r.refreshToken }, anonymous: true }).catch(() => {})
        setError('Use a KIOSK device account (create one under Users & devices).')
        return
      }
      setSession('kiosk', fromAuthResponse(r))
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <div className="center-page">
      {useAccount ? (
        <form className="card auth-card stack" onSubmit={signIn}>
          <div className="logo"><Logo large /></div>
          <h1>{title}</h1>
          <p className="muted small" style={{ textAlign: 'center' }}>Sign in once with the kiosk device account.</p>
          <ErrorAlert error={error} />
          <Field label="Kiosk username"><input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" required /></Field>
          <Field label="Password"><PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} required /></Field>
          <button className="btn primary block">Start kiosk</button>
          <button type="button" className="btn block" onClick={() => { setUseAccount(false); setError(null) }}>Use a device key instead</button>
        </form>
      ) : (
        <form className="card auth-card stack" onSubmit={(e) => { e.preventDefault(); void connect(key) }}>
          <div className="logo"><Logo large /></div>
          <h1>{title}</h1>
          <p className="muted small" style={{ textAlign: 'center' }}>
            Paste this kiosk's device key from <strong>Users &amp; devices</strong>, or open the setup link an administrator copied there.
            It is saved on this kiosk only, and the kiosk stays connected until the key is replaced.
          </p>
          <ErrorAlert error={error} />
          <Field label="Device key"><input className="mono" value={key} onChange={(e) => setKey(e.target.value)}
            autoComplete="off" spellCheck={false} placeholder="kd_…" required /></Field>
          <button className="btn primary block" disabled={busy || !key.trim()}>{busy ? 'Checking…' : 'Connect kiosk'}</button>
          <button type="button" className="btn block" onClick={() => { setUseAccount(true); setError(null) }}>Sign in with a kiosk account instead</button>
        </form>
      )}
    </div>
  )
}

/**
 * Invisible corner of a running kiosk: holding it for 5 seconds asks to disconnect the kiosk (removes its
 * key or sign-in) so staff can set it up again. Patrons won't trigger it by accident.
 */
export function KioskResetZone() {
  const timer = useRef<number | null>(null)
  const start = () => {
    timer.current = window.setTimeout(async () => {
      if (!window.confirm('Disconnect this kiosk? It will ask for its device key again.')) return
      setDeviceKey(null)
      await logout('kiosk')
      window.location.reload()
    }, RESET_HOLD_MS)
  }
  const cancel = () => { if (timer.current) window.clearTimeout(timer.current) }
  useEffect(() => cancel, [])
  return <div className="kiosk-reset-zone" aria-hidden onPointerDown={start} onPointerUp={cancel} onPointerLeave={cancel} onPointerCancel={cancel} />
}
