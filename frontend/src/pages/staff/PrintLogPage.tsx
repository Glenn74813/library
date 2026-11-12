import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { Page } from '../../api/types'
import { ErrorAlert, Field, Pager, Table } from '../../components/ui'
import { fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import type { PrintEntry, PrintKind } from '../../print/slips'

const KINDS: [PrintKind, string][] = [
  ['RECEIPT', 'Official receipts'], ['CLEARANCE', 'Library clearances'], ['ID_CARD', 'Library ID cards'],
  ['CARD_SLIP', 'Card slips'], ['PIN_SLIP', 'Card slips with PIN'], ['CHECKOUT_SLIP', 'Borrowing slips'],
  ['RETURN_SLIP', 'Return slips'], ['HOLD_SLIP', 'Hold shelf slips'], ['CASH_REPORT', 'End-of-day reports'],
]

/** Every document printed at the desks and kiosks, so staff can see what a patron was already given. */
export function PrintLogPage() {
  const [page, setPage] = useState(0)
  const [q, setQ] = useState('')
  const [kind, setKind] = useState('')
  const [reprintsOnly, setReprintsOnly] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const search = useDebounced(q.trim(), 300)
  const { data, error } = useAsync(() => api<Page<PrintEntry>>(`/api/print-log${qs({
    q: search || undefined, kind: kind || undefined, reprintsOnly: reprintsOnly || undefined,
    from: from || undefined, to: to || undefined, page })}`), [search, kind, reprintsOnly, from, to, page])
  const change = (fn: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => { fn(e.target.value); setPage(0) }

  return (
    <>
      <div className="page-head"><h1>Print log</h1></div>
      <ErrorAlert error={error} />
      <div className="card">
        <div className="form-grid" style={{ marginBottom: 8 }}>
          <Field label="Search"><input value={q} onChange={change(setQ)} placeholder="Patron, card, receipt or reference no., staff…" /></Field>
          <Field label="Document">
            <select value={kind} onChange={change(setKind)}>
              <option value="">All documents</option>
              {KINDS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
            </select>
          </Field>
          <Field label="From"><input type="date" value={from} onChange={change(setFrom)} /></Field>
          <Field label="To"><input type="date" value={to} onChange={change(setTo)} /></Field>
        </div>
        <label className="check" style={{ marginBottom: 8 }}>
          <input type="checkbox" checked={reprintsOnly} onChange={(e) => { setReprintsOnly(e.target.checked); setPage(0) }} />
          Reprints only (second and later copies)</label>
        <Table rows={data?.content} rowKey={(e) => e.id} empty="Nothing printed matches" columns={[
          { header: 'Printed', render: (e) => <span className="nowrap">{fmtDateTime(e.printedAt)}</span> },
          { header: 'Document', render: (e) => <>{e.label}{e.copy > 1 && <> <span className="badge warn">Reprint · copy {e.copy}</span></>}</> },
          { header: 'Reference', render: (e) => e.reference ? <span className="mono">{e.reference}</span> : '—' },
          { header: 'Patron', render: (e) => e.patronId
            ? <Link to={`/staff/patrons/${e.patronId}`}>{e.patronName}<div className="muted small mono">{e.cardNumber}</div></Link> : '—' },
          { header: 'Details', render: (e) => <span className="small">{e.details ?? '—'}</span> },
          { header: 'By', render: (e) => e.printedBy },
        ]} />
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}
