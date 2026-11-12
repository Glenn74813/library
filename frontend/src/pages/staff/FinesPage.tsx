import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { Fine, FineStatus, Page, Receipt } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { ReceiptModal } from '../../components/ReceiptModal'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table, Tabs, ExportMenu } from '../../components/ui'
import { fmtDate, fmtMoney, isoDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'
import { useLibrary } from '../../api/library'
import { CashReportSlip, printSlip, type CashReportData } from '../../print/slips'

export function FinesPage() {
  const [status, setStatus] = useState<FineStatus | 'ALL'>('UNPAID')
  const [page, setPage] = useState(0)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [reportOpen, setReportOpen] = useState(false)
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
        <div className="row">
          <button className="btn" onClick={() => setReportOpen(true)}><Icon name="printer" />End-of-day report</button>
          <ExportMenu path="/api/reports/export/fines" name="fines" />
        </div>
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
            <div className="row-actions">
              <button className="btn small primary" onClick={() => pay(f)}>Receive payment</button>
              {canWaive && <button className="btn small" onClick={() => waive(f)}>Waive</button>}
            </div>
          ) : f.receiptNumber ? <button className="btn small" onClick={() => showReceipt(f.receiptNumber!)}>Receipt</button>
            : <span className="muted small">{f.settledBy}</span> },
        ]} />
        <Pager page={data} onPage={setPage} />
      </div>
      {reportOpen && <CashReportModal onClose={() => setReportOpen(false)} />}
      {receipt && <ReceiptModal receipt={receipt} onClose={() => setReceipt(null)} />}
    </>
  )
}

/** Fines collected on one day, receipt by receipt and per cashier, to reconcile with the cash box. */
function CashReportModal({ onClose }: { onClose: () => void }) {
  const lib = useLibrary()
  const [date, setDate] = useState(isoDate())
  const { data, error } = useAsync(() => api<CashReportData>(`/api/fines/cash-report${qs({ date })}`), [date])
  return (
    <Modal title="End-of-day collection report" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Close</button>
      <button className="btn primary" disabled={!data} onClick={() => data && void printSlip(<CashReportSlip lib={lib} r={data} />)}>
        <Icon name="printer" />Print on receipt printer</button></>}>
      <div className="stack">
        <Field label="Date"><input type="date" value={date} max={isoDate()} onChange={(e) => setDate(e.target.value)} /></Field>
        <ErrorAlert error={error} />
        {data && <>
          <Table rows={data.receipts} rowKey={(r) => r.receiptNumber} empty="No payments received on this day" columns={[
            { header: 'Receipt', render: (r) => <span className="mono">{r.receiptNumber}</span> },
            { header: 'Patron', render: (r) => <>{r.patronName}<div className="muted small">{r.cardNumber}</div></> },
            { header: 'Cashier', render: (r) => r.cashier },
            { header: 'Amount', render: (r) => <span className="nowrap">{fmtMoney(r.amount)}</span> },
          ]} />
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <strong>Total collected: {fmtMoney(data.total)}</strong>
            {data.waivedCount > 0 && <span className="muted small">Waived: {data.waivedCount} ({fmtMoney(data.waivedTotal)}), not collected</span>}
          </div>
          {data.byCashier.length > 1 && <div className="muted small">{data.byCashier.map((c) => `${c.cashier}: ${fmtMoney(c.total)} (${c.receipts})`).join(' · ')}</div>}
        </>}
      </div>
    </Modal>
  )
}
