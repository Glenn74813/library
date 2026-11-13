import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, download, qs } from '../../api/client'
import type { Page, Patron } from '../../api/types'
import { PatronFormModal } from '../../components/PatronForm'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, Table } from '../../components/ui'
import { fmtDate, fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'
import { HelpButton } from '../../components/HelpButton'

export function PatronsPage() {
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [verifying, setVerifying] = useState(false)
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
        <h1>Patrons<HelpButton /></h1>
        <div className="row">
          <button className="btn" onClick={() => download('/api/patrons/import-template', 'patron-import-template.csv')}><Icon name="download" />Template</button>
          <button className="btn" onClick={() => fileRef.current?.click()}><Icon name="upload" />Bulk import CSV</button>
          <input ref={fileRef} type="file" accept=".csv,text/csv" hidden onChange={(e) => e.target.files?.[0] && importFile(e.target.files[0])} />
          <button className="btn" onClick={() => setVerifying(true)}><Icon name="check-circle" />Verify clearance</button>
          <button className="btn primary" onClick={() => setCreating(true)}><Icon name="plus" />Register</button>
        </div>
      </div>
      <div className="card">
        <input type="search" placeholder="Search by name, ID number or email…" value={q} style={{ marginBottom: 12 }}
          onChange={(e) => { setQ(e.target.value); setPage(0) }} aria-label="Search patrons" />
        <ErrorAlert error={error} />
        <Table rows={data?.content} rowKey={(p) => p.id} onRowClick={(p) => navigate(`/staff/patrons/${p.id}`)} empty="No patrons found" columns={[
          { header: 'Name', render: (p) => <strong>{p.lastName}, {p.firstName}</strong> },
          { header: 'ID no.', render: (p) => <span className="mono">{p.cardNumber}</span> },
          { header: 'Category', render: (p) => p.categoryName },
          { header: 'Course', render: (p) => p.courseCode ?? '—' },
          { header: 'Expiry', render: (p) => p.expiryDate ? <span className={p.expiryDate < today ? 'badge bad' : ''}>{fmtDate(p.expiryDate)}</span> : '—' },
          { header: 'Status', render: (p) => p.blocked ? <span className="badge bad">Blocked</span> : <span className="badge ok">Active</span> },
        ]} />
        <Pager page={data} onPage={setPage} />
      </div>
      {verifying && <VerifyClearanceModal onClose={() => setVerifying(false)} />}
      {creating && <PatronFormModal patron={null} onClose={() => setCreating(false)} onSaved={(p) => navigate(`/staff/patrons/${p.id}?new=1`)} />}
    </>
  )
}

interface Clearance { reference: string; patronName: string; cardNumber: string; category: string; course: string | null
  purpose: string | null; issuedAt: string; issuedBy: string; stillClear: boolean }

/** Checks a clearance slip someone presents (e.g. the registrar), by its reference number. */
function VerifyClearanceModal({ onClose }: { onClose: () => void }) {
  const [ref, setRef] = useState('')
  const [result, setResult] = useState<Clearance | null>(null)
  const [error, setError] = useState<string | null>(null)
  async function check(e: React.FormEvent) {
    e.preventDefault()
    setError(null); setResult(null)
    try { setResult(await api<Clearance>(`/api/clearances/${encodeURIComponent(ref.trim())}`)) } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title="Verify a library clearance" onClose={onClose} footer={<button className="btn" onClick={onClose}>Close</button>}>
      <form className="stack" onSubmit={check}>
        <Field label="Clearance number" hint="Printed on the slip, e.g. CLR-2026-000012; its QR code holds the same number">
          <div className="input-group">
            <input className="mono" value={ref} onChange={(e) => setRef(e.target.value)} autoFocus placeholder="CLR-…" />
            <button className="btn primary" disabled={!ref.trim()}>Check</button>
          </div>
        </Field>
        <ErrorAlert error={error} />
        {result && (
          <div className={`alert ${result.stillClear ? 'success' : 'warn'}`}>
            <strong className="mono">{result.reference}</strong> is genuine: issued to <strong>{result.patronName}</strong> ({result.cardNumber},
            {' '}{[result.category, result.course].filter(Boolean).join(' · ')}) on {fmtDateTime(result.issuedAt)} by {result.issuedBy}
            {result.purpose && <> for <em>{result.purpose}</em></>}.
            <div style={{ marginTop: 6 }}>{result.stillClear ? 'They are still clear today.'
              : 'Note: they have borrowed items or unpaid fines again since it was issued.'}</div>
          </div>
        )}
      </form>
    </Modal>
  )
}
