import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, logout } from '../../api/client'
import { useSession } from '../../auth/AuthContext'
import { Field } from '../../components/ui'
import { errorText, useToast } from '../../components/Toast'

export function AccountPage() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const toast = useToast()
  const navigate = useNavigate()
  const mustChange = useSession()?.user.mustChangePassword

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (next !== confirm) return toast('New passwords do not match', 'error')
    try {
      await api('/api/auth/change-password', { body: { currentPassword: current, newPassword: next } })
      toast('Password changed. Please sign in again.')
      await logout()
      navigate('/login')
    } catch (err) {
      toast(errorText(err), 'error')
    }
  }

  return (
    <>
      <div className="page-head"><h1>My account</h1></div>
      {mustChange && <div className="alert warn" style={{ maxWidth: 480, marginBottom: 16 }}>
        <strong>Choose your own password.</strong> Your password was set by an administrator. Enter it as the current
        password, then choose a new one to continue.</div>}
      <form className="card stack" style={{ maxWidth: 480 }} onSubmit={submit}>
        <h2>Change password</h2>
        <Field label="Current password">
          <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
        </Field>
        <Field label="New password" hint="At least 8 characters">
          <input type="password" autoComplete="new-password" minLength={8} value={next} onChange={(e) => setNext(e.target.value)} required />
        </Field>
        <Field label="Confirm new password">
          <input type="password" autoComplete="new-password" minLength={8} value={confirm} onChange={(e) => setConfirm(e.target.value)} required />
        </Field>
        <div className="row end"><button className="btn primary">Change password</button></div>
      </form>
    </>
  )
}
