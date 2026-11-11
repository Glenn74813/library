import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { reloadLibraryConfig, type LibraryProfile } from '../../api/library'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'

interface SystemSettings {
  profile: LibraryProfile
  kiosks: { opacResetSeconds: number; duplicateTapSeconds: number; selfCheckRequirePin: boolean; selfCheckAllowReturns: boolean }
  sessions: { accessTokenMinutes: number; refreshTokenDays: number; deviceTokenDays: number }
  uploads: { ebookMaxMb: number; audioMaxMb: number; photoMaxMb: number }
}

/** Library profile, kiosk timers, sign-in session lengths and upload limits (ADMIN). */
export function SystemSettingsPage() {
  const toast = useToast()
  const { data, error } = useAsync(() => api<SystemSettings>('/api/settings/system'), [])
  const [f, setF] = useState<SystemSettings | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (data) setF(data) }, [data])
  if (error) return <ErrorAlert error={error} />
  if (!f) return <p className="muted">Loading…</p>

  const profile = (patch: Partial<LibraryProfile>) => setF({ ...f, profile: { ...f.profile, ...patch } })
  const section = <K extends 'kiosks' | 'sessions' | 'uploads'>(k: K, key: keyof SystemSettings[K]) =>
    (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: { ...f[k], [key]: Number(e.target.value) } })
  const text = (key: keyof LibraryProfile) => ({
    value: f.profile[key], onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => profile({ [key]: e.target.value }),
  })

  async function save() {
    setBusy(true)
    try {
      setF(await api<SystemSettings>('/api/settings/system', { method: 'PUT', body: f }))
      await reloadLibraryConfig()
      toast('System settings saved')
    } catch (e) { toast(errorText(e), 'error') } finally { setBusy(false) }
  }

  return (
    <>
      <div className="page-head">
        <h1>System settings</h1>
        <button className="btn primary" disabled={busy} onClick={save}>Save</button>
      </div>
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>Library profile</h2>
          <p className="muted small" style={{ margin: 0 }}>Shown on every screen, receipts, PDF reports, e-mails, the kiosks and self-check stations.</p>
          <div className="form-grid">
            <Field label="System name" hint="Top bar, home page and browser tab"><input maxLength={80} {...text('systemName')} /></Field>
            <Field label="Short name" hint="E-mail subjects, catalog title, SIP2 institution"><input maxLength={20} {...text('shortName')} /></Field>
            <Field label="Library name" className="span-all"><input maxLength={120} {...text('name')} /></Field>
            <Field label="School / institution" className="span-all"><input maxLength={160} {...text('institution')} /></Field>
            <Field label="Address" className="span-all"><input maxLength={250} {...text('address')} /></Field>
            <Field label="Phone"><input maxLength={60} {...text('phone')} /></Field>
            <Field label="E-mail"><input type="email" maxLength={120} {...text('email')} /></Field>
            <Field label="Website" className="span-all"><input maxLength={200} {...text('website')} /></Field>
            <Field label="Receipt header" className="span-all" hint="Extra lines under the address, e.g. TIN or cashier office">
              <textarea rows={2} maxLength={500} {...text('receiptHeader')} /></Field>
            <Field label="Receipt footer" className="span-all"><textarea rows={2} maxLength={500} {...text('receiptFooter')} /></Field>
          </div>
        </div>
        <div className="stack">
          <div className="card stack">
            <h2 style={{ margin: 0 }}>Kiosks</h2>
            <div className="form-grid">
              <Field label="OPAC kiosk reset (seconds)" hint="Search kiosks clear the screen after this much inactivity">
                <input type="number" min={15} max={3600} value={f.kiosks.opacResetSeconds} onChange={section('kiosks', 'opacResetSeconds')} /></Field>
              <Field label="Ignore repeat taps (seconds)" hint="Attendance kiosk ignores the same card tapped again within this time; 0 = never">
                <input type="number" min={0} max={3600} value={f.kiosks.duplicateTapSeconds} onChange={section('kiosks', 'duplicateTapSeconds')} /></Field>
              <Field label="Self-checkout" as="div" className="span-all" hint="Kiosk at /kiosk/selfcheck; the same loan rules as the desk, without overrides">
                <div className="stack" style={{ gap: 4 }}>
                  <label className="check"><input type="checkbox" checked={f.kiosks.selfCheckRequirePin}
                    onChange={(e) => setF({ ...f, kiosks: { ...f.kiosks, selfCheckRequirePin: e.target.checked } })} /> Ask for the patron's PIN</label>
                  <label className="check"><input type="checkbox" checked={f.kiosks.selfCheckAllowReturns}
                    onChange={(e) => setF({ ...f, kiosks: { ...f.kiosks, selfCheckAllowReturns: e.target.checked } })} /> Allow returns at the kiosk</label>
                </div>
              </Field>
            </div>
          </div>
          <div className="card stack">
            <h2 style={{ margin: 0 }}>Sign-in sessions</h2>
            <p className="muted small" style={{ margin: 0 }}>Changes apply to new sign-ins and new device tokens.</p>
            <div className="form-grid">
              <Field label="Access token (minutes)" hint="Renewed automatically while someone is using the system">
                <input type="number" min={5} max={240} value={f.sessions.accessTokenMinutes} onChange={section('sessions', 'accessTokenMinutes')} /></Field>
              <Field label="Stay signed in (days)" hint="Staff, patrons and kiosks must sign in again after this long without use">
                <input type="number" min={1} max={90} value={f.sessions.refreshTokenDays} onChange={section('sessions', 'refreshTokenDays')} /></Field>
              <Field label="Gate device token (days)" hint="Lifetime of tokens issued to security gate middleware; revoke them under Users & devices">
                <input type="number" min={1} max={1825} value={f.sessions.deviceTokenDays} onChange={section('sessions', 'deviceTokenDays')} /></Field>
            </div>
          </div>
          <div className="card stack">
            <h2 style={{ margin: 0 }}>Upload limits (MB)</h2>
            <div className="form-grid">
              <Field label="E-books (PDF / EPUB)"><input type="number" min={1} max={500} value={f.uploads.ebookMaxMb} onChange={section('uploads', 'ebookMaxMb')} /></Field>
              <Field label="Announcement recordings"><input type="number" min={1} max={500} value={f.uploads.audioMaxMb} onChange={section('uploads', 'audioMaxMb')} /></Field>
              <Field label="Patron photos"><input type="number" min={1} max={500} value={f.uploads.photoMaxMb} onChange={section('uploads', 'photoMaxMb')} /></Field>
            </div>
            <p className="muted small" style={{ margin: 0 }}>The server accepts at most 500 MB per file.</p>
          </div>
        </div>
      </div>
      <div className="row end" style={{ marginTop: 16 }}><button className="btn primary" disabled={busy} onClick={save}>Save</button></div>
    </>
  )
}
