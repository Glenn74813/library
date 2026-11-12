import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { useLibrary } from '../../api/library'
import { Logo } from '../../components/Logo'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, PasswordInput } from '../../components/ui'

/** Patron "Forgot PIN": e-mail a one-time code, then set a new PIN with it. */
export function ForgotPin() {
  const lib = useLibrary()
  const toast = useToast()
  const navigate = useNavigate()
  const [step, setStep] = useState<'request' | 'confirm'>('request')
  const [card, setCard] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [pin, setPin] = useState('')
  const [pin2, setPin2] = useState('')
  const [info, setInfo] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function request(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      const r = await api<{ message: string }>('/api/auth/pin-reset/request', { anonymous: true, body: { card: card.trim(), email: email.trim() } })
      setInfo(r.message); setStep('confirm')
    } catch (err) { setError(errorText(err)) } finally { setBusy(false) }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    if (pin !== pin2) return setError('The two PINs do not match')
    setBusy(true); setError(null)
    try {
      const r = await api<{ message: string }>('/api/auth/pin-reset/confirm', { anonymous: true, body: { card: card.trim(), code: code.trim(), newPin: pin } })
      toast(r.message)
      navigate('/my/login')
    } catch (err) { setError(errorText(err)) } finally { setBusy(false) }
  }

  return (
    <div className="center-page">
      <form className="card auth-card stack" onSubmit={step === 'request' ? request : confirm}>
        <div className="logo"><Logo large /></div>
        <h1>Forgot PIN</h1>
        <div className="school">{lib.name}</div>
        <ErrorAlert error={error} />
        {step === 'request' ? <>
          <p className="muted small" style={{ margin: 0 }}>Enter your ID number and the e-mail address the library has for you.
            We'll send a code to set a new PIN.</p>
          <Field label="Library ID number"><input value={card} onChange={(e) => setCard(e.target.value)} required autoFocus /></Field>
          <Field label="E-mail address"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></Field>
          <button className="btn primary block" disabled={busy}>{busy ? 'Sending…' : 'Send code'}</button>
        </> : <>
          {info && <div className="alert">{info}</div>}
          <Field label="6-digit code"><input inputMode="numeric" pattern="\d{6}" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} required autoFocus /></Field>
          <Field label="New PIN (4–8 digits)"><PasswordInput inputMode="numeric" pattern="\d{4,8}" value={pin} onChange={(e) => setPin(e.target.value)} required /></Field>
          <Field label="New PIN again"><PasswordInput inputMode="numeric" pattern="\d{4,8}" value={pin2} onChange={(e) => setPin2(e.target.value)} required /></Field>
          <button className="btn primary block" disabled={busy}>{busy ? 'Saving…' : 'Set new PIN'}</button>
          <button type="button" className="btn block" onClick={() => { setStep('request'); setInfo(null) }}>Send another code</button>
        </>}
        <p className="muted small" style={{ margin: 0, textAlign: 'center' }}>No e-mail on record? Ask at the circulation desk.
          {' '}<Link to="/my/login">Back to sign in</Link></p>
      </form>
    </div>
  )
}
