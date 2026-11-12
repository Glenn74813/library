import { useRef, useState, type FormEvent } from 'react'
import { api } from '../../api/client'
import type { LibrarySection, ScanResult, StocktakeReport, StocktakeSession } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { CameraScanButton } from '../../components/CameraScan'
import { ErrorAlert, Field, Modal, Stat, StatusBadge, Table, ExportMenu } from '../../components/ui'
import { fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

export function InventoryPage() {
  const { data, error, reload } = useAsync(() => api<StocktakeSession[]>('/api/inventory/sessions'), [])
  const [active, setActive] = useState<StocktakeSession | null>(null)
  const [creating, setCreating] = useState(false)

  if (active) return <SessionView session={active} onBack={() => { setActive(null); void reload() }} />

  return (
    <>
      <div className="page-head">
        <h1>Stocktake / shelf inventory</h1>
        <button className="btn primary" onClick={() => setCreating(true)}><Icon name="plus" />Start stocktake</button>
      </div>
      <ErrorAlert error={error} />
      <div className="card">
        <Table rows={data} rowKey={(s) => s.id} onRowClick={setActive} empty="No stocktakes yet" columns={[
          { header: 'Name', render: (s) => <strong>{s.name}</strong> },
          { header: 'Location', render: (s) => s.location ?? 'Whole collection' },
          { header: 'Started', render: (s) => <>{fmtDateTime(s.startedAt)}<div className="muted small">{s.startedBy}</div></> },
          { header: 'Scanned', render: (s) => s.scanned },
          { header: 'Status', render: (s) => s.closedAt ? <span className="badge">Closed</span> : <span className="badge ok">Open</span> },
        ]} />
      </div>
      {creating && <NewSession onClose={() => setCreating(false)} onCreated={(s) => { setCreating(false); setActive(s) }} />}
    </>
  )
}

function NewSession({ onClose, onCreated }: { onClose: () => void; onCreated: (s: StocktakeSession) => void }) {
  const [name, setName] = useState(`Stocktake ${new Date().toLocaleDateString()}`)
  const [location, setLocation] = useState('')
  const { data: sections } = useAsync(() => api<LibrarySection[]>('/api/sections'), [])
  const [error, setError] = useState<string | null>(null)
  async function create() {
    try {
      onCreated(await api<StocktakeSession>('/api/inventory/sessions', { body: { name, location } }))
    } catch (e) {
      setError(errorText(e))
    }
  }
  return (
    <Modal title="Start stocktake" onClose={onClose} footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={create}>Start</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Section / area" hint="Copies from another section are reported as misplaced. Sections are listed under Collection → Sections.">
          <select value={location} onChange={(e) => setLocation(e.target.value)}>
            <option value="">Whole collection (no misplaced check)</option>
            {sections?.map((s) => <option key={s.id} value={s.name}>{s.name} ({s.copies})</option>)}
          </select></Field>
      </div>
    </Modal>
  )
}

function SessionView({ session, onBack }: { session: StocktakeSession; onBack: () => void }) {
  const [code, setCode] = useState('')
  const [scans, setScans] = useState<ScanResult[]>([])
  const [report, setReport] = useState<StocktakeReport | null>(null)
  const [closed, setClosed] = useState(!!session.closedAt)
  const ref = useRef<HTMLInputElement>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const toast = useToast()
  const canClose = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')

  async function scan(e: FormEvent) {
    e.preventDefault()
    if (!code.trim()) return
    try {
      const r = await api<ScanResult>(`/api/inventory/sessions/${session.id}/scan`, { body: { code: code.trim() } })
      setScans((s) => [r, ...s].slice(0, 100))
    } catch (err) {
      toast(errorText(err), 'error')
    }
    setCode('')
    ref.current?.focus()
  }

  async function loadReport() {
    setReport(await api<StocktakeReport>(`/api/inventory/sessions/${session.id}/report`))
  }

  async function close() {
    const markMissing = confirm('Close this stocktake.\n\nOK = also mark unscanned AVAILABLE items as MISSING\nCancel = just close')
    try {
      await api(`/api/inventory/sessions/${session.id}/close`, { body: { markMissing } })
      setClosed(true)
      toast('Stocktake closed')
      void loadReport()
    } catch (err) {
      toast(errorText(err), 'error')
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>{session.name}</h1>
        <div className="row">
          <button className="btn" onClick={onBack}><Icon name="chevron-left" />Back</button>
          <button className="btn" onClick={loadReport}>Missing/found report</button>
          {!closed && canClose && <button className="btn danger" onClick={close}>Close session</button>}
        </div>
      </div>
      <p className="muted">{session.location ? `Location: ${session.location}` : 'Whole collection'}{closed && ' · closed'}</p>
      {!closed && (
        <form className="card" onSubmit={scan} style={{ marginBottom: 16 }} ref={formRef}>
          <label className="field"><span>Scan each item on the shelf (barcode or RFID)</span>
            <div className="input-group">
              <input ref={ref} className="scan-input" value={code} onChange={(e) => setCode(e.target.value)} autoFocus autoComplete="off" />
              <CameraScanButton onScan={(t) => { setCode(t); setTimeout(() => formRef.current?.requestSubmit(), 0) }} />
              <button className="btn primary">Add</button>
            </div>
          </label>
        </form>
      )}
      {report ? (
        <div className="stack">
          <div className="stats">
            <Stat label="Expected on shelf" value={report.expected} />
            <Stat label="Found" value={report.found} />
            <Stat label="Missing" value={report.missing.length} tone={report.missing.length ? 'bad' : undefined} />
            <Stat label="Misplaced" value={report.misplaced.length} tone={report.misplaced.length ? 'warn' : undefined} />
            <Stat label="Status mismatch" value={report.unexpectedStatus.length} tone={report.unexpectedStatus.length ? 'warn' : undefined} />
            <Stat label="Unknown codes" value={report.unknownCodes.length} />
          </div>
          <div className="row"><ExportMenu path={`/api/inventory/sessions/${session.id}/report/export`} name="stocktake" label="Download report…" /></div>
          {[['Missing', report.missing], ['Misplaced', report.misplaced], ['Found with unexpected status', report.unexpectedStatus]].map(([title, list]) => (
            <div className="card" key={title as string}>
              <h2>{title as string}</h2>
              <Table rows={list as StocktakeReport['missing']} rowKey={(i) => i.barcode} empty="None" columns={[
                { header: 'Barcode', render: (i) => <span className="mono">{i.barcode}</span> },
                { header: 'Title', render: (i) => i.title },
                { header: 'Call no.', render: (i) => i.callNumber ?? '—' },
                { header: 'Location', render: (i) => i.location ?? '—' },
                { header: 'Status', render: (i) => <StatusBadge status={i.status} /> },
              ]} />
            </div>
          ))}
        </div>
      ) : (
        <div className="card">
          <h2>Recent scans</h2>
          <Table rows={scans} rowKey={(s) => s.code + s.message} empty="Scan items to begin" columns={[
            { header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
            { header: 'Result', render: (s) => <StatusBadge status={s.result} /> },
            { header: 'Title', render: (s) => s.title ?? '—' },
            { header: 'Note', render: (s) => s.message },
          ]} />
        </div>
      )}
    </>
  )
}
