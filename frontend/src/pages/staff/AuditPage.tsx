import { useState } from 'react'
import { api, qs } from '../../api/client'
import type { AuditEntry, Page } from '../../api/types'
import { ErrorAlert, ExportMenu, Field, Pager, Table } from '../../components/ui'
import { fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'

export function AuditPage() {
  const [page, setPage] = useState(0)
  const [q, setQ] = useState('')
  const [actor, setActor] = useState('')
  const [action, setAction] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const filters = { q: useDebounced(q.trim(), 300), actor: useDebounced(actor.trim(), 300), action, from, to }
  const query = qs(Object.fromEntries(Object.entries(filters).filter(([, v]) => v)))
  const { data: actions } = useAsync(() => api<string[]>('/api/audit/actions'), [])
  const { data, error } = useAsync(() => api<Page<AuditEntry>>(`/api/audit${qs({ ...Object.fromEntries(Object.entries(filters).filter(([, v]) => v)), page })}`),
    [query, page])
  const change = (fn: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => { fn(e.target.value); setPage(0) }

  return (
    <>
      <div className="page-head">
        <h1>Audit trail</h1>
        <ExportMenu path={`/api/audit/export${query}`} name="audit-trail" />
      </div>
      <ErrorAlert error={error} />
      <div className="card">
        <div className="form-grid" style={{ marginBottom: 8 }}>
          <Field label="Search details"><input value={q} onChange={change(setQ)} placeholder="Barcode, ID number, title…" /></Field>
          <Field label="User"><input value={actor} onChange={change(setActor)} placeholder="Username or sip2:station" /></Field>
          <Field label="Action">
            <select value={action} onChange={change(setAction)}>
              <option value="">All actions</option>
              {actions?.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </Field>
          <Field label="From"><input type="date" value={from} onChange={change(setFrom)} /></Field>
          <Field label="To"><input type="date" value={to} onChange={change(setTo)} /></Field>
        </div>
        <Table rows={data?.content} rowKey={(a) => a.id} empty="No matching entries" columns={[
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
