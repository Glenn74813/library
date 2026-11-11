import { api } from '../../api/client'
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
    { header: 'Status', render: (h: Hold) => <StatusBadge status={h.status} /> },
    { header: 'Placed', render: (h: Hold) => fmtDateTime(h.placedAt) },
    { header: 'Details', render: (h: Hold) => h.status === 'READY'
      ? <>Copy <span className="mono">{h.itemBarcode}</span> · pick up by {fmtDate(h.expiresAt)}</>
      : `Queue #${h.queuePosition}` },
    { header: '', render: (h: Hold) => <button className="btn small danger" onClick={() => cancel(h)}>Cancel</button> },
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
    </>
  )
}
