import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { login, logout } from '../../api/client'
import { STAFF_ROLES } from '../../auth/AuthContext'
import { ErrorAlert, Field } from '../../components/ui'
import { errorText } from '../../components/Toast'
import { Logo } from '../../components/Logo'
import { Icon } from '../../components/Icon'

export function StaffLogin() {
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
        <div className="school">Zamboanga State College of Marine Sciences and Technology</div>
        <ErrorAlert error={error} />
        <Field label="Username">
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        </Field>
        <Field label="Password">
          <input type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        <button className="btn primary block" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <nav className="auth-links" aria-label="Other sign-in options">
          <Link to="/my/login"><Icon name="user" />Patron sign in</Link>
          <Link to="/opac"><Icon name="search" />Search the catalog</Link>
        </nav>
      </form>
    </div>
  )
}
