import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { Fine, FineStatus, Page, Receipt } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { ReceiptModal } from '../../components/ReceiptModal'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Pager, StatusBadge, Table, Tabs, ExportMenu } from '../../components/ui'
import { fmtDate, fmtMoney } from '../../format'
import { useAsync } from '../../hooks/useAsync'

export function FinesPage() {
  const [status, setStatus] = useState<FineStatus | 'ALL'>('UNPAID')
  const [page, setPage] = useState(0)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const canWaive = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const toast = useToast()
  const { data, error, reload } = useAsync(
    () => api<Page<Fine>>(`/api/fines${qs({ status: status === 'ALL' ? undefined : status, page })}`), [status, page])

  async function pay(f: Fine) {
    if (!confirm(`Receive ${fmtMoney(f.amount)} from ${f.patronName}?`)) return
    try {
      setReceipt(await api<Receipt>('/api/fines/pay', { body: { fineIds: [f.id] } }))
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function waive(f: Fine) {
    const reason = prompt(`Reason for waiving ${fmtMoney(f.amount)} for ${f.patronName}:`)
    if (!reason) return
    try {
      await api(`/api/fines/${f.id}/waive`, { body: { reason } })
      toast('Fine waived')
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function showReceipt(no: string) {
    try {
      setReceipt(await api<Receipt>(`/api/fines/receipts/${encodeURIComponent(no)}`))
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Fines & payments</h1>
        <ExportMenu path="/api/reports/export/fines" name="fines" />
      </div>
      <p className="muted small">To pay several fines on one receipt, open the patron's record.</p>
      <Tabs value={status} onChange={(s) => { setStatus(s); setPage(0) }} tabs={[
        { id: 'UNPAID', label: 'Unpaid' }, { id: 'PAID', label: 'Paid' }, { id: 'WAIVED', label: 'Waived' }, { id: 'ALL', label: 'All' }]} />
      <ErrorAlert error={error} />
      <div className="card">
        <Table rows={data?.content} rowKey={(f) => f.id} empty="No fines" columns={[
          { header: 'Date', render: (f) => fmtDate(f.createdAt) },
          { header: 'Patron', render: (f) => <Link to={`/staff/patrons/${f.patronId}`}>{f.patronName}</Link> },
          { header: 'Reason', render: (f) => f.reason },
          { header: 'Amount', render: (f) => <strong>{fmtMoney(f.amount)}</strong> },
          { header: 'Status', render: (f) => <StatusBadge status={f.status} /> },
          { header: '', render: (f) => f.status === 'UNPAID' ? (
            <div className="row">
              <button className="btn small primary" onClick={() => pay(f)}>Receive payment</button>
              {canWaive && <button className="btn small" onClick={() => waive(f)}>Waive</button>}
            </div>
          ) : f.receiptNumber ? <button className="btn small" onClick={() => showReceipt(f.receiptNumber!)}>Receipt</button>
            : <span className="muted small">{f.settledBy}</span> },
        ]} />
        <Pager page={data} onPage={setPage} />
      </div>
      {receipt && <ReceiptModal receipt={receipt} onClose={() => setReceipt(null)} />}
    </>
  )
}
