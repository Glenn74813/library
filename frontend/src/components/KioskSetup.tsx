import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, login, logout, setDeviceKey } from '../api/client'
import { ErrorAlert, Field, PasswordInput } from './ui'
import { errorText } from './Toast'
import { Logo } from './Logo'

/** Hold the top-left corner of a kiosk screen this long to disconnect it (staff only). */
const RESET_HOLD_MS = 5000

/**
 * One-time setup of a kiosk PC (attendance or self-checkout). The usual way is the device key
 * issued under Users & devices, pasted here or opened as a setup link (…?key=…). Signing in with a
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
    setBusy(true)
    setError(null)
    setDeviceKey(candidate)
    try {
      // Any kiosk endpoint proves the key works; a wrong key is cleared by the client on 401.
      await api('/api/kiosk/config', { slot: 'kiosk' })
    } catch (err) {
      setDeviceKey(null)
      setError(/not accepted|401|Unauthorized/i.test(errorText(err))
        ? 'This key was not accepted. Check that it was copied completely and that it is the device\'s current key.'
        : errorText(err))
    } finally {
      setBusy(false)
    }
  }

  // Setup link: /kiosk/attendance?key=kd_… stores the key and removes it from the address bar.
  const handledLink = useRef(false)
  useEffect(() => {
    if (handledLink.current) return
    handledLink.current = true
    const params = new URLSearchParams(window.location.search)
    const fromLink = params.get('key')
    if (!fromLink) return
    params.delete('key')
    const rest = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : ''))
    void connect(fromLink)
  }, [])

  async function signIn(e: FormEvent) {
    e.preventDefault()
    try {
      const user = await login(username, password, 'kiosk')
      if (!user.roles.some((r) => ['KIOSK', 'ADMIN', 'LIBRARIAN'].includes(r))) {
        await logout('kiosk')
        setError('Use a KIOSK device account (create one under Users & devices).')
      }
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
  return <div className="kiosk-reset-zone" aria-hidden onPointerDown={start} onPointerUp={cancel} onPointerLeave={cancel} onPointerCancel={cancel} />
}
