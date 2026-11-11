import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { login, logout } from '../../api/client'
import { STAFF_ROLES } from '../../auth/AuthContext'
import { ErrorAlert, Field, PasswordInput } from '../../components/ui'
import { errorText } from '../../components/Toast'
import { Logo } from '../../components/Logo'
import { useLibrary } from '../../api/library'

export function StaffLogin() {
  const lib = useLibrary()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/staff'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const user = await login(username, password)
      if (!user.roles.some((r) => STAFF_ROLES.includes(r))) {
        await logout()
        setError('This account is not a staff account. Kiosk accounts sign in from the kiosk screen.')
        return
      }
      navigate(from, { replace: true })
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="center-page">
      <form className="card auth-card stack" onSubmit={submit}>
        <div className="logo"><Logo large /></div>
        <h1>Staff sign in</h1>
        <div className="school">{lib.institution || lib.name}</div>
        <ErrorAlert error={error} />
        <Field label="Username">
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        </Field>
        <Field label="Password" hint="Forgot it? An administrator can set a temporary one under Users & devices">
          <PasswordInput autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        <button className="btn primary block" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  )
}
