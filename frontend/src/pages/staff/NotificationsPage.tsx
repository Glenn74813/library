import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { errorText, useToast } from '../../components/Toast'
import { Field, StatusBadge, Table, PasswordInput } from '../../components/ui'
import { fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'

type NoticeType = 'DUE_SOON' | 'OVERDUE' | 'HOLD_READY' | 'RENEWAL_APPROVED' | 'RENEWAL_REJECTED'

interface Settings {
  mailEnabled: boolean; mailHost: string; mailPort: number; mailUsername: string; mailPassword: string; passwordSet: boolean
  mailFrom: string; mailStarttls: boolean
  smsEnabled: boolean; smsUrl: string; smsApiKey: string; apiKeySet: boolean; smsSender: string
  sendHour: number; dueSoonDays: number
  templates: Record<NoticeType, string>
}

interface NoticeRow {
  id: number; patronName: string; cardNumber: string; type: NoticeType; message: string; createdAt: string
  sentAt: string | null; channels: string | null; attempts: number; lastError: string | null
}

const TEMPLATE_LABEL: Record<NoticeType, string> = {
  DUE_SOON: 'Due soon', OVERDUE: 'Overdue', HOLD_READY: 'Reserved item ready for pickup',
  RENEWAL_APPROVED: 'Renewal approved', RENEWAL_REJECTED: 'Renewal rejected ({reason} = the reason you gave)',
}

/** Notice delivery (e-mail / SMS), schedule and editable message templates. */
export function NotificationsPage() {
  const { data } = useAsync(() => api<Settings>('/api/notifications/settings'), [])
  const { data: recent, reload } = useAsync(() => api<NoticeRow[]>('/api/notifications/recent'), [])
  const [s, setS] = useState<Settings | null>(null)
  const [testTo, setTestTo] = useState('')
  const [testChannel, setTestChannel] = useState<'EMAIL' | 'SMS'>('EMAIL')
  const toast = useToast()
  useEffect(() => { if (data) setS(data) }, [data])
  if (!s) return <p className="muted">Loading…</p>
  const set = <K extends keyof Settings>(k: K, v: Settings[K]) => setS({ ...s, [k]: v })

  async function save() {
    try {
      setS(await api<Settings>('/api/notifications/settings', { method: 'PUT', body: s }))
      toast('Notification settings saved')
    } catch (e) { toast(errorText(e), 'error') }
  }

  async function test() {
    try {
      const r = await api<{ result: string }>('/api/notifications/test', { body: { channel: testChannel, to: testTo } })
      toast(r.result)
    } catch (e) { toast(errorText(e), 'error') }
  }

  return (
    <>
      <div className="page-head">
        <h1>Notifications</h1>
        <button className="btn primary" onClick={save}>Save settings</button>
      </div>
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>E-mail (SMTP)</h2>
          <label className="check"><input type="checkbox" checked={s.mailEnabled} onChange={(e) => set('mailEnabled', e.target.checked)} /> Send notices by e-mail</label>
          <div className="form-grid">
            <Field label="SMTP server" hint="e.g. smtp.gmail.com"><input value={s.mailHost} onChange={(e) => set('mailHost', e.target.value)} /></Field>
            <Field label="Port" hint="587 (STARTTLS) or 465"><input type="number" value={s.mailPort} onChange={(e) => set('mailPort', Number(e.target.value))} /></Field>
            <Field label="Username"><input value={s.mailUsername} autoComplete="off" onChange={(e) => set('mailUsername', e.target.value)} /></Field>
            <Field label="Password" hint={s.passwordSet ? 'Saved — leave empty to keep' : 'App password for the mailbox'}>
              <PasswordInput value={s.mailPassword} autoComplete="new-password" onChange={(e) => set('mailPassword', e.target.value)} /></Field>
            <Field label="Sender address" hint="e.g. library@zscmst.edu.ph"><input type="email" value={s.mailFrom} onChange={(e) => set('mailFrom', e.target.value)} /></Field>
            <Field label="Security" as="div"><label className="check"><input type="checkbox" checked={s.mailStarttls} onChange={(e) => set('mailStarttls', e.target.checked)} /> Use STARTTLS</label></Field>
          </div>
        </div>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>SMS gateway</h2>
          <label className="check"><input type="checkbox" checked={s.smsEnabled} onChange={(e) => set('smsEnabled', e.target.checked)} /> Send notices by SMS</label>
          <div className="form-grid">
            <Field label="Gateway URL" className="span-all" hint={'Receives POST {"to", "message", "sender"} with the API key as a Bearer token'}>
              <input type="url" value={s.smsUrl} onChange={(e) => set('smsUrl', e.target.value)} /></Field>
            <Field label="API key" hint={s.apiKeySet ? 'Saved — leave empty to keep' : ''}>
              <PasswordInput value={s.smsApiKey} autoComplete="new-password" onChange={(e) => set('smsApiKey', e.target.value)} /></Field>
            <Field label="Sender name"><input value={s.smsSender} maxLength={30} onChange={(e) => set('smsSender', e.target.value)} /></Field>
          </div>
          <h3 style={{ margin: '8px 0 0' }}>Send a test</h3>
          <div className="input-group">
            <select value={testChannel} onChange={(e) => setTestChannel(e.target.value as 'EMAIL' | 'SMS')} style={{ flex: '0 0 110px' }}>
              <option value="EMAIL">E-mail</option><option value="SMS">SMS</option>
            </select>
            <input value={testTo} placeholder={testChannel === 'EMAIL' ? 'name@example.com' : '09171234567'} onChange={(e) => setTestTo(e.target.value)} />
            <button className="btn" onClick={test}>Send test</button>
          </div>
          <p className="muted small" style={{ margin: 0 }}>Save settings first; the test uses the saved settings.</p>
        </div>
      </div>
      <div className="card stack">
        <h2 style={{ margin: 0 }}>Schedule & messages</h2>
        <div className="form-grid">
          <Field label="Daily send time" hint="Due-soon and overdue notices go out once a day at this hour">
            <select value={s.sendHour} onChange={(e) => set('sendHour', Number(e.target.value))}>
              {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{`${h % 12 === 0 ? 12 : h % 12}:00 ${h < 12 ? 'AM' : 'PM'}`}</option>)}
            </select>
          </Field>
          <Field label="Due-soon reminder" hint="Days before the due date">
            <input type="number" min={1} max={14} value={s.dueSoonDays} onChange={(e) => set('dueSoonDays', Number(e.target.value))} />
          </Field>
          {(Object.keys(TEMPLATE_LABEL) as NoticeType[]).map((t) => (
            <Field key={t} label={`${TEMPLATE_LABEL[t]} message`} className="span-all" hint="Placeholders: {name} first name, {title} book title, {due} date">
              <textarea value={s.templates[t]} maxLength={600} onChange={(e) => set('templates', { ...s.templates, [t]: e.target.value })} />
            </Field>
          ))}
        </div>
        <div className="row end"><button className="btn primary" onClick={save}>Save settings</button></div>
      </div>
      <div className="card">
        <div className="page-head" style={{ marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>Recent notices</h2>
          <button className="btn small" onClick={() => void reload()}>Refresh</button>
        </div>
        <Table rows={recent} rowKey={(n) => n.id} empty="No notices yet" columns={[
          { header: 'Created', render: (n) => fmtDateTime(n.createdAt) },
          { header: 'Patron', render: (n) => <>{n.patronName}<div className="muted small">{n.cardNumber}</div></> },
          { header: 'Type', render: (n) => TEMPLATE_LABEL[n.type] },
          { header: 'Message', render: (n) => <span className="small">{n.message}</span> },
          { header: 'Delivery', render: (n) => n.sentAt
            ? <>{n.channels === 'NONE' ? <span className="badge">No contact info</span> : <StatusBadge status="OK" />} <span className="small muted">{n.channels !== 'NONE' && n.channels}</span></>
            : n.attempts >= 5 ? <span className="badge bad" title={n.lastError ?? ''}>Failed</span>
              : n.attempts > 0 ? <span className="badge warn" title={n.lastError ?? ''}>Retrying ({n.attempts})</span>
                : <span className="badge info">Queued</span> },
        ]} />
      </div>
    </>
  )
}
