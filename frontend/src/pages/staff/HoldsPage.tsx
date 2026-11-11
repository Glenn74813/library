import { api, releaseHold } from '../../api/client'
import type { Hold } from '../../api/types'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, StatusBadge, Table } from '../../components/ui'
import { fmtDate, fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'

export function HoldsPage() {
  const { data, error, reload } = useAsync(() => api<Hold[]>('/api/holds'), [])
  const toast = useToast()
  const ready = data?.filter((h) => h.status === 'READY')
  const pending = data?.filter((h) => h.status === 'PENDING')
  const booked = data?.filter((h) => h.status === 'SCHEDULED').sort((a, b) => (a.pickupDate ?? '').localeCompare(b.pickupDate ?? ''))

  async function release(h: Hold) {
    if (!confirm(`Check out "${h.title}" (copy ${h.itemBarcode}) to ${h.patronName}?`)) return
    try {
      const r = await releaseHold(h)
      toast(`Released to ${h.patronName} — due ${fmtDate(r.loan.dueAt)}${r.notice ? ` (${r.notice})` : ''}`)
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function cancel(h: Hold) {
    if (!confirm(`Cancel hold of ${h.patronName} on "${h.title}"?`)) return
    try {
      await api(`/api/holds/${h.id}`, { method: 'DELETE' })
      toast('Hold cancelled')
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  const cols = [
    { header: 'Title', render: (h: Hold) => h.title },
    { header: 'Patron', render: (h: Hold) => <>{h.patronName}<div className="muted small">{h.cardNumber}</div></> },
    { header: 'Status', render: (h: Hold) => <span className="row" style={{ gap: 4 }}><StatusBadge status={h.status} />
      {h.atRisk && <span className="badge bad" title="No copy on the shelf or due back in time">At risk</span>}</span> },
    { header: 'Placed', render: (h: Hold) => fmtDateTime(h.placedAt) },
    { header: 'Details', render: (h: Hold) => h.status === 'SCHEDULED'
      ? <>Pickup on <strong>{fmtDate(h.pickupDate)}</strong> · copy set aside that morning</>
      : h.status === 'READY'
      ? <>Copy <span className="mono">{h.itemBarcode}</span>{h.itemShelfLocation && <> from <strong>{h.itemShelfLocation}</strong></>} · pick up by {fmtDate(h.expiresAt)}</>
      : h.pickupDate ? <>Booked for <strong>{fmtDate(h.pickupDate)}</strong> · first in line for the next returned copy</>
      : `Queue #${h.queuePosition}` },
    { header: '', render: (h: Hold) => <span className="row">
      {h.status === 'READY' && <button className="btn small primary" onClick={() => release(h)}>Release (check out)</button>}
      <button className="btn small danger" onClick={() => cancel(h)}>Cancel</button>
    </span> },
  ]

  return (
    <>
      <div className="page-head"><h1>Holds / reservations</h1></div>
      <ErrorAlert error={error} />
      <div className="card">
        <h2>On the hold shelf ({ready?.length ?? 0})</h2>
        <Table rows={ready} rowKey={(h) => h.id} columns={cols} empty="No items waiting for pickup" />
      </div>
      <div className="card">
        <h2>Waiting for a copy ({pending?.length ?? 0})</h2>
        <Table rows={pending} rowKey={(h) => h.id} columns={cols} empty="No pending holds" />
      </div>
      <div className="card">
        <h2>Booked for later ({booked?.length ?? 0})</h2>
        <p className="muted small" style={{ marginTop: 0 }}>Copies keep circulating until the pickup date; loans that would collide are shortened automatically.</p>
        <Table rows={booked} rowKey={(h) => h.id} columns={cols} empty="No advance bookings" />
      </div>
    </>
  )
}
