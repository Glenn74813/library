import { useState } from 'react'
import { api, qs } from '../../api/client'
import type { AuditEntry, Page } from '../../api/types'
import { ErrorAlert, Pager, Table } from '../../components/ui'
import { fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'

export function AuditPage() {
  const [page, setPage] = useState(0)
  const { data, error } = useAsync(() => api<Page<AuditEntry>>(`/api/audit${qs({ page })}`), [page])
  return (
    <>
      <div className="page-head"><h1>Audit trail</h1></div>
      <ErrorAlert error={error} />
      <div className="card">
        <Table rows={data?.content} rowKey={(a) => a.id} columns={[
          { header: 'Time', render: (a) => <span className="nowrap">{fmtDateTime(a.createdAt)}</span> },
          { header: 'User', render: (a) => a.actor },
          { header: 'Action', render: (a) => <span className="badge info">{a.action}</span> },
          { header: 'Details', render: (a) => <span className="small">{a.details ?? '—'}</span> },
        ]} />
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}
