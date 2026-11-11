import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, download, qs } from '../../api/client'
import type { Loan, Page } from '../../api/types'
import { ErrorAlert, Pager, Table, Tabs } from '../../components/ui'
import { fmtDate, fmtMoney } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

export function LoansPage() {
  const [tab, setTab] = useState<'all' | 'overdue'>('overdue')
  const [page, setPage] = useState(0)
  const navigate = useNavigate()
  const { data, error } = useAsync(
    () => api<Page<Loan>>(`/api/circulation/loans${qs({ overdue: tab === 'overdue', page })}`), [tab, page])

  return (
    <>
      <div className="page-head">
        <h1>Loans</h1>
        <button className="btn" onClick={() => download('/api/reports/export/overdue', 'overdue.csv')}><Icon name="download" />Overdue (Excel/CSV)</button>
      </div>
      <Tabs value={tab} onChange={(t) => { setTab(t); setPage(0) }}
        tabs={[{ id: 'overdue', label: 'Overdue' }, { id: 'all', label: 'All active loans' }]} />
      <ErrorAlert error={error} />
      <div className="card">
        <Table rows={data?.content} rowKey={(l) => l.id} onRowClick={(l) => navigate(`/staff/patrons/${l.patronId}`)}
          empty={tab === 'overdue' ? 'No overdue items' : 'No active loans'} columns={[
            { header: 'Title', render: (l) => <>{l.title}<div className="muted small mono">{l.barcode}</div></> },
            { header: 'Patron', render: (l) => <>{l.patronName}<div className="muted small">{l.cardNumber}</div></> },
            { header: 'Borrowed', render: (l) => fmtDate(l.checkoutAt) },
            { header: 'Due', render: (l) => <span className="nowrap">{fmtDate(l.dueAt)} {l.overdue && <span className="badge bad">{l.overdueDays}d</span>}</span> },
            { header: 'Accrued fine', render: (l) => (l.accruedFine > 0 ? fmtMoney(l.accruedFine) : '—') },
          ]} />
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}
