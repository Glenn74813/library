import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { patronLogin } from '../../api/client'
import { ErrorAlert, Field } from '../../components/ui'
import { errorText } from '../../components/Toast'
import { Logo } from '../../components/Logo'
import { Icon } from '../../components/Icon'

export function PatronLogin() {
  const [card, setCard] = useState('')
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  const [params] = useSearchParams()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await patronLogin(card.trim(), pin)
      const reserve = params.get('reserve')
      const next = params.get('next')
      navigate(next?.startsWith('/') && !next.startsWith('//') ? next : reserve ? `/my?reserve=${reserve}` : '/my', { replace: true })
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
        <h1>My Library</h1>
        <div className="school">Zamboanga State College of Marine Sciences and Technology</div>
        <ErrorAlert error={error} />
        <Field label="Library card / ID number"><input value={card} onChange={(e) => setCard(e.target.value)} autoComplete="username" required autoFocus /></Field>
        <Field label="PIN" hint="Get your PIN at the circulation desk">
          <input type="password" inputMode="numeric" value={pin} onChange={(e) => setPin(e.target.value)} autoComplete="current-password" required />
        </Field>
        <button className="btn primary block" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <nav className="auth-links" aria-label="Other options">
          <Link to="/opac"><Icon name="search" />Search the catalog</Link>
          <Link to="/login"><Icon name="briefcase" />Staff sign in</Link>
        </nav>
      </form>
    </div>
  )
}
