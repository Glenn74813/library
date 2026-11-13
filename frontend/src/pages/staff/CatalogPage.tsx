import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { hasRole, useSession } from '../../auth/AuthContext'
import { ImportCatalogModal } from '../../components/ImportCatalogModal'
import { api, download, qs } from '../../api/client'
import { MATERIAL_TYPES, type BibRecord, type Page } from '../../api/types'
import { EbookBadge, ErrorAlert, Pager, Table } from '../../components/ui'
import { label } from '../../format'
import { errorText, useToast } from '../../components/Toast'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'
import { CameraScanButton } from '../../components/CameraScan'
import { ItemModal } from './RecordPage'

export function CatalogPage() {
  const [q, setQ] = useState('')
  const [field, setField] = useState('any')
  const [type, setType] = useState('')
  const [page, setPage] = useState(0)
  const dq = useDebounced(q)
  const navigate = useNavigate()
  const session = useSession()
  const canImport = hasRole(session, 'ADMIN', 'LIBRARIAN')
  const canDeleteRecord = hasRole(session, 'LIBRARIAN')
  const [addingTo, setAddingTo] = useState<BibRecord | null>(null)
  const [importing, setImporting] = useState(false)
  const toast = useToast()
  const { data, error, reload } = useAsync(
    () => api<Page<BibRecord>>(`/api/catalog/records${qs({ q: dq, field, type, page })}`), [dq, field, type, page])

  async function remove(r: BibRecord) {
    if (!confirm(`Delete "${r.title}" and all ${r.totalCopies} of its copies? This cannot be undone.`)) return
    try {
      await api(`/api/catalog/records/${r.id}`, { method: 'DELETE' })
      toast('Record deleted')
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Catalog</h1>
        <div className="row">
          {canImport && <button className="btn" onClick={() => setImporting(true)}><Icon name="upload" />Import</button>}
          <select className="btn" style={{ width: 'auto' }} value="" aria-label="Export catalog" onChange={(e) => {
            const v = e.target.value
            if (v === 'csv') void download('/api/reports/export/collection', 'collection.csv')
            if (v === 'pdf') void download('/api/reports/export/collection?format=pdf', 'collection.pdf')
            if (v === 'mrc') void download('/api/catalog/export/marc?format=mrc', 'catalog.mrc')
            if (v === 'xml') void download('/api/catalog/export/marc?format=xml', 'catalog.xml')
          }}>
            <option value="" disabled>Export…</option>
            <option value="csv">Excel / CSV (copies)</option>
            <option value="pdf">PDF (copies)</option>
            {canImport && <option value="mrc">MARC21 (.mrc)</option>}
            {canImport && <option value="xml">MARCXML (.xml)</option>}
          </select>
          <Link className="btn primary" to="/staff/catalog/new"><Icon name="plus" />New record</Link>
        </div>
      </div>
      <div className="card">
        <div className="form-grid" style={{ marginBottom: 12 }}>
          <div className="input-group span-all">
            <input type="search" placeholder="Search title, author, subject, ISBN, call number, or scan a copy's barcode / RFID…" value={q}
              onChange={(e) => { setQ(e.target.value); setPage(0) }} aria-label="Search catalog" />
            <CameraScanButton title="Scan a copy's barcode" onScan={(t) => { setQ(t); setPage(0) }} />
          </div>
          <select value={field} onChange={(e) => { setField(e.target.value); setPage(0) }} aria-label="Search field">
            <option value="any">Keyword</option><option value="title">Title</option><option value="author">Author</option>
            <option value="subject">Subject</option><option value="isbn">ISBN</option><option value="callno">Call number</option>
            <option value="barcode">Copy barcode / RFID</option>
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
            // Row click opens the title (copies and borrowing history); these act on it directly.
            { header: 'Actions', className: 'no-row-click', render: (r) => <span className="row-actions icon-actions" onClick={(e) => e.stopPropagation()}>
              <button className="btn small icon" title="Add copy" aria-label={`Add a copy of ${r.title}`}
                onClick={() => setAddingTo(r)}><Icon name="plus" /></button>
              <button className="btn small icon" title="Edit record" aria-label={`Edit ${r.title}`}
                onClick={() => navigate(`/staff/catalog/${r.id}/edit`)}><Icon name="edit-2" /></button>
              {canDeleteRecord && <button className="btn small icon danger" title="Delete record" aria-label={`Delete ${r.title}`}
                onClick={() => void remove(r)}><Icon name="trash-2" /></button>}
            </span> },
          ]} />
        <Pager page={data} onPage={setPage} />
      </div>
      {addingTo && <ItemModal recordId={addingTo.id} item={{ status: 'AVAILABLE' }} canDelete={false} others={[]}
        title={`Add a copy of “${addingTo.title}”`}
        onClose={() => setAddingTo(null)} onSaved={() => { setAddingTo(null); void reload() }} />}
      {importing && <ImportCatalogModal onClose={() => setImporting(false)} onDone={() => setPage(0)} />}
    </>
  )
}
