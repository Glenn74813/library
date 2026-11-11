import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, download, qs } from '../../api/client'
import type { Page, Patron } from '../../api/types'
import { PatronFormModal } from '../../components/PatronForm'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Pager, Table } from '../../components/ui'
import { fmtDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'

export function PatronsPage() {
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const dq = useDebounced(q)
  const navigate = useNavigate()
  const toast = useToast()
  const fileRef = useRef<HTMLInputElement>(null)
  const { data, error, reload } = useAsync(() => api<Page<Patron>>(`/api/patrons${qs({ q: dq, page })}`), [dq, page])

  async function importFile(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    try {
      const r = await api<{ created: number; updated: number }>('/api/patrons/import', { body: fd })
      toast(`Import done: ${r.created} added, ${r.updated} updated`)
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    } finally {
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  const today = new Date().toISOString().slice(0, 10)

  return (
    <>
      <div className="page-head">
        <h1>Patrons</h1>
        <div className="row">
          <button className="btn" onClick={() => download('/api/patrons/import-template', 'patron-import-template.csv')}><Icon name="download" />Template</button>
          <button className="btn" onClick={() => fileRef.current?.click()}><Icon name="upload" />Bulk import CSV</button>
          <input ref={fileRef} type="file" accept=".csv,text/csv" hidden onChange={(e) => e.target.files?.[0] && importFile(e.target.files[0])} />
          <button className="btn primary" onClick={() => setCreating(true)}><Icon name="plus" />Register</button>
        </div>
      </div>
      <div className="card">
        <input type="search" placeholder="Search by name, card number or email…" value={q} style={{ marginBottom: 12 }}
          onChange={(e) => { setQ(e.target.value); setPage(0) }} aria-label="Search patrons" />
        <ErrorAlert error={error} />
        <Table rows={data?.content} rowKey={(p) => p.id} onRowClick={(p) => navigate(`/staff/patrons/${p.id}`)} empty="No patrons found" columns={[
          { header: 'Name', render: (p) => <strong>{p.lastName}, {p.firstName}</strong> },
          { header: 'Card no.', render: (p) => <span className="mono">{p.cardNumber}</span> },
          { header: 'Category', render: (p) => p.categoryName },
          { header: 'Course', render: (p) => p.courseCode ?? '—' },
          { header: 'Expiry', render: (p) => p.expiryDate ? <span className={p.expiryDate < today ? 'badge bad' : ''}>{fmtDate(p.expiryDate)}</span> : '—' },
          { header: 'Status', render: (p) => p.blocked ? <span className="badge bad">Blocked</span> : <span className="badge ok">Active</span> },
        ]} />
        <Pager page={data} onPage={setPage} />
      </div>
      {creating && <PatronFormModal patron={null} onClose={() => setCreating(false)} onSaved={(p) => navigate(`/staff/patrons/${p.id}`)} />}
    </>
  )
}
