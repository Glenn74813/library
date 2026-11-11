import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, download, qs } from '../../api/client'
import { MATERIAL_TYPES, type BibRecord, type Page } from '../../api/types'
import { EbookBadge, ErrorAlert, Pager, Table } from '../../components/ui'
import { label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'

export function CatalogPage() {
  const [q, setQ] = useState('')
  const [field, setField] = useState('any')
  const [type, setType] = useState('')
  const [page, setPage] = useState(0)
  const dq = useDebounced(q)
  const navigate = useNavigate()
  const { data, error } = useAsync(
    () => api<Page<BibRecord>>(`/api/catalog/records${qs({ q: dq, field, type, page })}`), [dq, field, type, page])

  return (
    <>
      <div className="page-head">
        <h1>Catalog</h1>
        <div className="row">
          <button className="btn" onClick={() => download('/api/reports/export/collection', 'collection.csv')}><Icon name="download" />Export</button>
          <Link className="btn primary" to="/staff/catalog/new"><Icon name="plus" />New record</Link>
        </div>
      </div>
      <div className="card">
        <div className="form-grid" style={{ marginBottom: 12 }}>
          <input className="span-all" type="search" placeholder="Search title, author, subject, ISBN, call number…" value={q}
            onChange={(e) => { setQ(e.target.value); setPage(0) }} aria-label="Search catalog" />
          <select value={field} onChange={(e) => { setField(e.target.value); setPage(0) }} aria-label="Search field">
            <option value="any">Keyword</option><option value="title">Title</option><option value="author">Author</option>
            <option value="subject">Subject</option><option value="isbn">ISBN</option><option value="callno">Call number</option>
          </select>
          <select value={type} onChange={(e) => { setType(e.target.value); setPage(0) }} aria-label="Material type">
            <option value="">All material types</option>
            {MATERIAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
        </div>
        <ErrorAlert error={error} />
        <Table rows={data?.content} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/staff/catalog/${r.id}`)}
          empty="No records found" columns={[
            { header: 'Title', render: (r) => <><strong>{r.title}</strong>{r.subtitle && <span className="muted"> : {r.subtitle}</span>}
              <div className="muted small">{r.authors}</div></> },
            { header: 'Call no.', render: (r) => <span className="mono small">{r.callNumber ?? '—'}</span> },
            { header: 'Type', render: (r) => <>{label(r.materialType)}{r.ebook && <div><EbookBadge ebook={r.ebook} /></div>}</> },
            { header: 'Year', render: (r) => r.publicationYear ?? '—' },
            { header: 'Available', render: (r) => <span className={`badge ${r.availableCopies ? 'ok' : r.totalCopies ? 'warn' : ''}`}>
              {r.availableCopies} / {r.totalCopies}</span> },
          ]} />
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}
