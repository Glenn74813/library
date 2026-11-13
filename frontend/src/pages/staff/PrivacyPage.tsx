import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field } from '../../components/ui'
import { fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { HelpButton } from '../../components/HelpButton'

interface PrivacySettings {
  notice: string
  officer: string
  contact: string
  retainLoanYears: number
  retainAttendanceYears: number
  retainGateDays: number
  retainNoticeDays: number
  retainAuditYears: number
  anonymizeExpiredYears: number
  lastRun: string | null
}

interface RetentionResult { dryRun: boolean; counts: Record<string, number> }

const LIMITS: { key: keyof PrivacySettings; label: string; unit: string; max: number; hint: string }[] = [
  { key: 'retainLoanYears', label: 'Loan, fine & reservation history', unit: 'years', max: 50,
    hint: 'Settled history older than this is detached from the patron; totals and charts stay correct' },
  { key: 'retainAttendanceYears', label: 'Library visits (attendance)', unit: 'years', max: 50,
    hint: 'Visits older than this are anonymized; visitor counts are kept' },
  { key: 'anonymizeExpiredYears', label: 'Expired patrons (graduated, resigned)', unit: 'years after expiry', max: 50,
    hint: 'Patrons with nothing on loan and no unpaid fines are anonymized this long after their library ID expires' },
  { key: 'retainGateDays', label: 'Security gate log', unit: 'days', max: 3650, hint: 'Gate events older than this are deleted' },
  { key: 'retainNoticeDays', label: 'Sent notices', unit: 'days', max: 3650, hint: 'Copies of sent email/SMS notices older than this are deleted' },
  { key: 'retainAuditYears', label: 'Audit trail', unit: 'years', max: 50, hint: 'Staff action log entries older than this are deleted' },
]

const COUNT_LABELS: Record<string, string> = {
  loansAnonymized: 'Loans anonymized', finesAnonymized: 'Fines anonymized', reservationsAnonymized: 'Reservations anonymized',
  ebookViewsAnonymized: 'E-book views anonymized', visitsAnonymized: 'Visits anonymized', gateEventsDeleted: 'Gate events deleted',
  noticesDeleted: 'Notices deleted', auditEntriesDeleted: 'Audit entries deleted', expiredPatronsAnonymized: 'Expired patrons anonymized',
}

/** Data Privacy Act (RA 10173): privacy notice, data protection officer, and retention limits. */
export function PrivacyPage() {
  const toast = useToast()
  const { data, error, reload } = useAsync(() => api<PrivacySettings>('/api/privacy/settings'), [])
  const [f, setF] = useState<PrivacySettings | null>(null)
  const [result, setResult] = useState<RetentionResult | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => { if (data) setF(data) }, [data])
  if (error) return <ErrorAlert error={error} />
  if (!f) return <p className="muted">Loading…</p>

  async function save() {
    try {
      setF(await api<PrivacySettings>('/api/privacy/settings', { method: 'PUT', body: f }))
      setResult(null)
      toast('Privacy settings saved')
    } catch (e) { toast(errorText(e), 'error') }
  }

  async function run(dryRun: boolean) {
    if (!dryRun && !window.confirm('Apply the retention limits now? Anonymized and deleted records cannot be restored.')) return
    setBusy(true)
    try {
      setResult(await api<RetentionResult>(`/api/privacy/retention/run?dryRun=${dryRun}`, { method: 'POST' }))
      if (!dryRun) { toast('Retention limits applied'); void reload() }
    } catch (e) { toast(errorText(e), 'error') } finally { setBusy(false) }
  }

  const set = (patch: Partial<PrivacySettings>) => setF({ ...f, ...patch })
  const counts = result ? Object.entries(result.counts) : []

  return (
    <>
      <div className="page-head"><h1>Data privacy<HelpButton /></h1></div>
      <p className="muted" style={{ marginTop: -8 }}>Data Privacy Act of 2012 (RA 10173). The notice is shown to patrons on the OPAC, the patron portal
        and at sign-in. Patrons' data can be downloaded or erased from each patron's page.</p>
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>Privacy notice</h2>
          <div className="form-grid">
            <Field label="Data Protection Officer"><input value={f.officer} maxLength={150} onChange={(e) => set({ officer: e.target.value })} /></Field>
            <Field label="Contact (email or office)"><input value={f.contact} maxLength={150} onChange={(e) => set({ contact: e.target.value })} /></Field>
            <Field label="Notice shown to patrons" className="span-all" hint="What is collected, why, who sees it, how long it is kept, and the patron's rights">
              <textarea rows={11} value={f.notice} maxLength={5000} onChange={(e) => set({ notice: e.target.value })} /></Field>
          </div>
          <div className="row end"><a className="btn" href="/privacy" target="_blank" rel="noreferrer">View public page</a>
            <button className="btn primary" onClick={save}>Save</button></div>
        </div>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>Retention limits</h2>
          <p className="muted small" style={{ margin: 0 }}>0 keeps records forever. Limits are applied every night at 2:30 AM.
            Records still needed (items on loan, unpaid fines, open reservations) are never touched.</p>
          <div className="form-grid">
            {LIMITS.map((l) => (
              <Field key={l.key} label={`${l.label} (${l.unit})`} hint={l.hint} className="span-all">
                <input type="number" min={0} max={l.max} value={f[l.key] as number}
                  onChange={(e) => set({ [l.key]: Math.max(0, Number(e.target.value) || 0) } as Partial<PrivacySettings>)} />
              </Field>
            ))}
          </div>
          <div className="muted small">Last applied: {f.lastRun ? fmtDateTime(f.lastRun) : 'never'}</div>
          <div className="row end">
            <button className="btn" disabled={busy} onClick={() => run(true)}>Preview</button>
            <button className="btn" disabled={busy} onClick={() => run(false)}>Apply now</button>
            <button className="btn primary" onClick={save}>Save</button>
          </div>
          {result && (
            <div className={`alert ${result.dryRun ? '' : 'success'}`}>
              <strong>{result.dryRun ? 'Preview (saved limits, nothing changed yet)' : 'Applied'}</strong>
              {counts.length === 0 ? <div>Every limit is 0, so nothing is removed.</div> : (
                <ul>{counts.map(([k, n]) => <li key={k}>{COUNT_LABELS[k] ?? k}: {n.toLocaleString()}</li>)}</ul>
              )}
            </div>
          )}
        </div>
      </div>
    </>
  )
}
