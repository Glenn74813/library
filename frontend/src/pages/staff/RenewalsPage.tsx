import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { Page, RenewalRequest } from '../../api/types'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table, Tabs } from '../../components/ui'
import { fmtDate, fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'

const CHANNEL: Record<RenewalRequest['channel'], string> = { PORTAL: 'My Library', KIOSK: 'Self-checkout', SIP2: 'Self-check station' }

/** Patrons' renewal requests: approve, or reject with a reason the patron receives. */
export function RenewalsPage() {
  const toast = useToast()
  const [tab, setTab] = useState<'pending' | 'decided'>('pending')
  const [page, setPage] = useState(0)
  const [rejecting, setRejecting] = useState<RenewalRequest | null>(null)
  const { data: pending, error, reload } = useAsync(() => api<RenewalRequest[]>('/api/renewals/pending'), [])
  const { data: decided, reload: reloadDecided } = useAsync(
    () => tab === 'decided' ? api<Page<RenewalRequest>>(`/api/renewals/decided${qs({ page })}`) : Promise.resolve(undefined), [tab, page])

  async function approve(r: RenewalRequest) {
    try {
      const done = await api<RenewalRequest>(`/api/renewals/${r.id}/approve`, { method: 'POST' })
      toast(`Approved: "${r.title}" for ${r.patronName} is now due ${fmtDate(done.newDueAt)}`)
      void reload(); void reloadDecided()
    } catch (e) { toast(errorText(e), 'error'); void reload() }
  }

  return (
    <>
      <div className="page-head"><h1>Renewal requests</h1></div>
      <p className="muted" style={{ marginTop: -8 }}>Renewals that patrons ask for in My Library, at the self-checkout kiosk or a self-check
        station wait here. The proposed due date already accounts for other patrons' reservations and bookings.</p>
      <ErrorAlert error={error} />
      <Tabs value={tab} onChange={(t) => { setTab(t); setPage(0) }} tabs={[
        { id: 'pending', label: `Waiting (${pending?.length ?? 0})` }, { id: 'decided', label: 'Decided' }]} />
      <div className="card">
        {tab === 'pending' ? (
          <Table rows={pending} rowKey={(r) => r.id} empty="No renewal requests waiting" columns={[
            { header: 'Patron', render: (r) => <><Link to={`/staff/patrons/${r.patronId}`}>{r.patronName}</Link><div className="muted small mono">{r.cardNumber}</div></> },
            { header: 'Item', render: (r) => <>{r.title}<div className="muted small mono">{r.barcode}</div></> },
            { header: 'Requested', render: (r) => <>{fmtDateTime(r.requestedAt)}<div className="muted small">{CHANNEL[r.channel]}</div></> },
            { header: 'Due now', render: (r) => <span className="nowrap">{fmtDate(r.dueAt)}</span> },
            { header: 'If approved', render: (r) => r.problem
              ? <span className="badge bad" title={r.problem}>Can't renew</span>
              : <strong className="nowrap">{fmtDate(r.proposedDueAt)}</strong> },
            { header: 'Renewals', render: (r) => `${r.renewals}/${r.maxRenewals}` },
            { header: '', render: (r) => <div className="stack" style={{ gap: 4 }}>
              {r.problem && <span className="small" style={{ color: 'var(--danger)' }}>{r.problem}</span>}
              <span className="row" style={{ flexWrap: 'nowrap' }}>
                <button className="btn small primary" disabled={!!r.problem} onClick={() => approve(r)}>Approve</button>
                <button className="btn small danger" onClick={() => setRejecting(r)}>Reject</button>
              </span>
            </div> },
          ]} />
        ) : (<>
          <Table rows={decided?.content} rowKey={(r) => r.id} empty="No decisions yet" columns={[
            { header: 'Patron', render: (r) => <><Link to={`/staff/patrons/${r.patronId}`}>{r.patronName}</Link><div className="muted small mono">{r.cardNumber}</div></> },
            { header: 'Item', render: (r) => r.title },
            { header: 'Decision', render: (r) => <StatusBadge status={r.status} /> },
            { header: 'Details', render: (r) => r.status === 'APPROVED' ? `New due date ${fmtDate(r.newDueAt)}` : r.reason ?? '—' },
            { header: 'By', render: (r) => <>{r.decidedBy}<div className="muted small">{fmtDateTime(r.decidedAt)}</div></> },
          ]} />
          <Pager page={decided} onPage={setPage} />
        </>)}
      </div>
      {rejecting && <RejectModal request={rejecting} onClose={() => setRejecting(null)}
        onDone={() => { setRejecting(null); void reload(); void reloadDecided() }} />}
    </>
  )
}

const REASONS = [
  'Another patron has reserved this title',
  'This title is booked by another patron',
  'Renewal limit reached',
  'Please settle your unpaid fines first',
  'The item is needed for a class / course reserve',
]

function RejectModal({ request, onClose, onDone }: { request: RenewalRequest; onClose: () => void; onDone: () => void }) {
  const toast = useToast()
  const [reason, setReason] = useState(request.problem ?? '')
  const [busy, setBusy] = useState(false)
  async function submit() {
    setBusy(true)
    try {
      await api(`/api/renewals/${request.id}/reject`, { body: { reason } })
      toast(`Rejected; ${request.patronName} will be told why`)
      onDone()
    } catch (e) { toast(errorText(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="Reject renewal" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn danger solid" disabled={busy || !reason.trim()} onClick={submit}>Reject</button></>}>
      <div className="stack">
        <p style={{ margin: 0 }}><strong>{request.patronName}</strong> asked to renew <strong>{request.title}</strong>, due {fmtDate(request.dueAt)}.
          They keep the original due date and receive this reason by e-mail/SMS and in My Library.</p>
        <div className="row" style={{ gap: 6 }}>
          {REASONS.map((r) => <button key={r} type="button" className="btn small" onClick={() => setReason(r)}>{r}</button>)}
        </div>
        <Field label="Reason for the patron" hint="Required">
          <textarea rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus /></Field>
      </div>
    </Modal>
  )
}
