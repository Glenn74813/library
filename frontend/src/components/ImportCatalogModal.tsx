import { useState } from 'react'
import { api, download } from '../api/client'
import { Icon } from './Icon'
import { errorText } from './Toast'
import { ErrorAlert, Modal, Stat, Table } from './ui'

interface PreviewRow { source: string; title: string; authors: string | null; isbn: string | null; materialType: string; copies: number; action: string }
interface Report {
  committed: boolean; titlesInFile: number; titlesCreated: number; titlesMatched: number
  copiesCreated: number; copiesSkipped: number; problems: string[]; sample: PreviewRow[]
}

/**
 * Catalog migration: MARC21 (.mrc), MARCXML (.xml) or CSV. Step 1 checks the file and shows a
 * validation report without saving; step 2 imports it.
 */
export function ImportCatalogModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(commit: boolean) {
    if (!file) return
    const fd = new FormData()
    fd.append('file', file)
    setBusy(true)
    setError(null)
    try {
      setReport(await api<Report>(`/api/catalog/import?commit=${commit}`, { body: fd }))
      if (commit) onDone()
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  const done = report?.committed
  return (
    <Modal title="Import catalog records" onClose={onClose} footer={<>
      <button className="btn" onClick={() => download('/api/catalog/import-template', 'catalog-import-template.csv')}><Icon name="download" />CSV template</button>
      <span className="spacer" />
      <button className="btn" onClick={onClose}>{done ? 'Close' : 'Cancel'}</button>
      {!done && (report
        ? <button className="btn primary" disabled={busy || report.titlesInFile === 0} onClick={() => run(true)}>
            {busy ? 'Importing…' : `Import ${report.titlesCreated} new title(s), ${report.copiesCreated} cop${report.copiesCreated === 1 ? 'y' : 'ies'}`}</button>
        : <button className="btn primary" disabled={busy || !file} onClick={() => run(false)}>{busy ? 'Checking…' : 'Check file'}</button>)}
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        {!report && <>
          <p className="muted small" style={{ margin: 0 }}>
            Upload an export from your previous system: <strong>MARC21</strong> (.mrc / .dat), <strong>MARCXML</strong> (.xml), or a
            <strong> CSV</strong> spreadsheet (one row per copy). Copies are read from MARC 952 (Koha) or 852 holdings ($p barcode).
            Titles already in the catalog (same ISBN, or same title and author) get the new copies instead of a duplicate record.
          </p>
          <input type="file" accept=".mrc,.dat,.marc,.xml,.csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setReport(null) }} />
        </>}
        {report && <>
          {done
            ? <div className="alert success">Import finished. Records and copies are now in the catalog.</div>
            : <div className="alert">Preview only — nothing has been saved yet. Review the report, then import.</div>}
          <div className="stats">
            <Stat label="Titles in file" value={report.titlesInFile} />
            <Stat label={done ? 'New titles' : 'Will create'} value={report.titlesCreated} />
            <Stat label="Matched existing" value={report.titlesMatched} />
            <Stat label={done ? 'Copies added' : 'Copies to add'} value={report.copiesCreated} />
            <Stat label="Copies skipped" value={report.copiesSkipped} tone={report.copiesSkipped ? 'warn' : undefined} />
          </div>
          {report.problems.length > 0 && (
            <div className="alert warn small"><strong>{report.problems.length} note(s):</strong>
              <ul style={{ maxHeight: 160, overflowY: 'auto' }}>{report.problems.map((p, i) => <li key={i}>{p}</li>)}</ul></div>
          )}
          <h3 style={{ margin: 0 }}>First {report.sample.length} title(s)</h3>
          <Table rows={report.sample} rowKey={(r) => r.source} columns={[
            { header: 'Source', render: (r) => <span className="small muted">{r.source}</span> },
            { header: 'Title', render: (r) => <><strong>{r.title}</strong><div className="muted small">{r.authors}</div></> },
            { header: 'ISBN', render: (r) => <span className="mono small">{r.isbn ?? '—'}</span> },
            { header: 'Copies', render: (r) => r.copies },
            { header: 'Action', render: (r) => <span className={`badge ${r.action === 'new title' ? 'ok' : 'info'}`}>{r.action}</span> },
          ]} />
        </>}
      </div>
    </Modal>
  )
}
