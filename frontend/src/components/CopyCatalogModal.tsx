import { useState, type FormEvent } from 'react'
import { api, qs } from '../api/client'
import type { MaterialType } from '../api/types'
import { label } from '../format'
import { errorText } from './Toast'
import { ErrorAlert, Field, Modal } from './ui'

export interface CopyCandidate {
  source: string; title: string | null; subtitle: string | null; authors: string | null; isbn: string | null
  publisher: string | null; publicationYear: number | null; edition: string | null; subjects: string | null
  callNumber: string | null; materialType: MaterialType; language: string | null; summary: string | null
}

/** Copy cataloging: search the Library of Congress (SRU / Z39.50) and use a record to fill the form. */
export function CopyCatalogModal({ initialIsbn, initialTitle, onUse, onClose }: {
  initialIsbn?: string; initialTitle?: string; onUse: (c: CopyCandidate) => void; onClose: () => void
}) {
  const [isbn, setIsbn] = useState(initialIsbn ?? '')
  const [title, setTitle] = useState(initialIsbn ? '' : initialTitle ?? '')
  const [author, setAuthor] = useState('')
  const [result, setResult] = useState<{ target: string; total: number; records: CopyCandidate[] } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function search(e?: FormEvent) {
    e?.preventDefault()
    setBusy(true)
    setError(null)
    try {
      setResult(await api(`/api/catalog/copycat${qs(isbn.trim() ? { isbn } : { title, author })}`))
    } catch (err) {
      setError(errorText(err))
      setResult(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="Copy-catalog from the Library of Congress" onClose={onClose}>
      <form className="stack" onSubmit={search}>
        <div className="form-grid">
          <Field label="ISBN" hint="Fastest and most exact"><input value={isbn} onChange={(e) => setIsbn(e.target.value)} /></Field>
          <Field label="or Title"><input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
          <Field label="and/or Author"><input value={author} onChange={(e) => setAuthor(e.target.value)} /></Field>
        </div>
        <div className="row end"><button className="btn primary" disabled={busy}>{busy ? 'Searching…' : 'Search'}</button></div>
        <ErrorAlert error={error} />
        {result && (result.records.length === 0
          ? <p className="muted">No records found at {result.target}. Try the title and author, or catalog it by hand.</p>
          : <div className="stack" style={{ gap: 8 }}>
              <p className="muted small" style={{ margin: 0 }}>{result.total} match(es) at {result.target}; showing {result.records.length}.</p>
              {result.records.map((c, i) => (
                <div key={i} className="card copycat-row">
                  <div style={{ minWidth: 0 }}>
                    <strong>{c.title}{c.subtitle ? `: ${c.subtitle}` : ''}</strong>
                    <div className="muted small">{[c.authors, c.publisher, c.publicationYear, c.edition].filter(Boolean).join(' · ')}</div>
                    <div className="small">{[c.isbn && `ISBN ${c.isbn}`, c.callNumber && `Call no. ${c.callNumber}`, label(c.materialType)].filter(Boolean).join(' · ')}</div>
                  </div>
                  <button type="button" className="btn small primary" onClick={() => onUse(c)}>Use this record</button>
                </div>
              ))}
            </div>)}
      </form>
    </Modal>
  )
}
