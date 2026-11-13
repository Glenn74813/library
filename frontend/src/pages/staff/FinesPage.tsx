import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { Fine, FineStatus, Page, Receipt } from '../../api/types'
import { hasRole, isCashierOnly, PAYMENT_ROLES, useSession } from '../../auth/AuthContext'
import { ReceiptModal } from '../../components/ReceiptModal'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table, Tabs, ExportMenu } from '../../components/ui'
import { fmtDate, fmtMoney, isoDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'
import { useLibrary } from '../../api/library'
import { CashReportSlip, printRecorded, SlipPreview, type CashReportData } from '../../print/slips'

/**
 * Fines & payments. The cashier receives payments here: find the patron, tick their unpaid fines and
 * receive them on one receipt. Library staff see the same lists; charges and waivers stay with librarians.
 */
export function FinesPage() {
  const [status, setStatus] = useState<FineStatus | 'ALL'>('UNPAID')
  const [page, setPage] = useState(0)
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const [selected, setSelected] = useState<Fine[]>([])
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [reportOpen, setReportOpen] = useState(false)
  const session = useSession()
  const canWaive = hasRole(session, 'ADMIN', 'LIBRARIAN')
  const canPay = hasRole(session, ...PAYMENT_ROLES)
  const cashierOnly = isCashierOnly(session)
  const toast = useToast()
  const { data, error, reload } = useAsync(
    () => api<Page<Fine>>(`/api/fines${qs({ status: status === 'ALL' ? undefined : status, q: dq.trim() || undefined, page })}`), [status, dq, page])
  const total = selected.reduce((t, f) => t + f.amount, 0)

  function toggle(f: Fine, on: boolean) {
    if (on && selected.length && selected[0].patronId !== f.patronId) {
      return toast(`One receipt is for one patron: ${selected[0].patronName}'s fines are selected`, 'warn')
    }
    setSelected((list) => on ? [...list, f] : list.filter((x) => x.id !== f.id))
  }

  async function receive(list: Fine[]) {
    const sum = list.reduce((t, f) => t + f.amount, 0)
    if (!confirm(`Receive ${fmtMoney(sum)} from ${list[0].patronName} for ${list.length} fine(s)?`)) return
    try {
      setReceipt(await api<Receipt>('/api/fines/pay', { body: { fineIds: list.map((f) => f.id) } }))
      setSelected([])
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
      <p className="muted small">{canPay
        ? 'Search for the patron, tick their unpaid fines and receive them on one receipt.'
        : 'Payments are received by the cashier. Charges are added and waived from the patron\'s page.'}</p>
      <Tabs value={status} onChange={(s) => { setStatus(s); setPage(0); setSelected([]) }} tabs={[
        { id: 'UNPAID', label: 'Unpaid' }, { id: 'PAID', label: 'Paid' }, { id: 'WAIVED', label: 'Waived' }, { id: 'ALL', label: 'All' }]} />
      <ErrorAlert error={error} />
      <div className="card stack">
        <div className="row">
          <input type="search" value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} style={{ flex: 1, minWidth: 220 }}
            placeholder="Search by patron name or ID number…" aria-label="Search fines by patron" />
          {canPay && selected.length > 0 && <>
            <button className="btn primary" onClick={() => void receive(selected)}><Icon name="credit-card" />
              Receive {fmtMoney(total)} ({selected.length})</button>
            <button className="btn" onClick={() => setSelected([])}>Clear</button>
          </>}
        </div>
        <Table rows={data?.content} rowKey={(f) => f.id} empty="No fines" columns={[
          ...(canPay ? [{ header: '', render: (f: Fine) => f.status === 'UNPAID' && <input type="checkbox" aria-label={`Select fine of ${f.patronName}`}
            checked={selected.some((x) => x.id === f.id)} onChange={(e) => toggle(f, e.target.checked)} /> }] : []),
          { header: 'Date', render: (f) => fmtDate(f.createdAt) },
          { header: 'Patron', render: (f) => <>{cashierOnly ? <strong>{f.patronName}</strong>
            : <Link to={`/staff/patrons/${f.patronId}`}>{f.patronName}</Link>}<div className="muted small mono">{f.cardNumber}</div></> },
          { header: 'Reason', render: (f) => f.reason },
          { header: 'Amount', render: (f) => <strong>{fmtMoney(f.amount)}</strong> },
          { header: 'Status', render: (f) => <StatusBadge status={f.status} /> },
          { header: 'Actions', render: (f) => f.status === 'UNPAID' ? (
            <div className="row-actions">
              {canPay ? <button className="btn small primary" onClick={() => void receive([f])}>Receive payment</button>
                : <span className="muted small">Pay at the cashier</span>}
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
      <button className="btn primary" disabled={!data} onClick={() => data && void printRecorded({ kind: 'CASH_REPORT', reference: `CASH-${data.date}`,
        details: `${data.receipts.length} receipt(s), ${fmtMoney(data.total)}` }, <CashReportSlip lib={lib} r={data} />)}>
        <Icon name="printer" />Print</button></>}>
      <div className="stack">
        <Field label="Date"><input type="date" value={date} max={isoDate()} onChange={(e) => setDate(e.target.value)} /></Field>
        <ErrorAlert error={error} />
        {/* The report exactly as it prints on the receipt printer. */}
        {data && <SlipPreview slip={<CashReportSlip lib={lib} r={data} />} title={`End-of-day report ${data.date}`} />}
      </div>
    </Modal>
  )
}
