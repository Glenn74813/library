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

type Source = 'loc' | 'loc-z3950' | 'nlp' | 'z3950'
const SOURCES: Record<Source, string> = {
  loc: 'Library of Congress', 'loc-z3950': 'Library of Congress (Z39.50)',
  nlp: 'National Library of the Philippines', z3950: 'Other Z39.50 server…',
}
const HINTS: Record<Source, string> = {
  loc: 'Best for foreign books (LC call numbers)',
  'loc-z3950': 'The same catalog over the Z39.50 protocol',
  nlp: 'Best for Philippine books (Dewey call numbers); a little slower',
  z3950: 'Any library catalog that offers Z39.50: enter its host:port/database',
}

/**
 * Copy cataloging: search the Library of Congress (SRU / Z39.50) or the National Library of the Philippines
 * (its public catalog) and use a record to fill the form.
 */
export function CopyCatalogModal({ initialIsbn, initialTitle, onUse, onClose }: {
  initialIsbn?: string; initialTitle?: string; onUse: (c: CopyCandidate) => void; onClose: () => void
}) {
  const [isbn, setIsbn] = useState(initialIsbn ?? '')
  const [title, setTitle] = useState(initialIsbn ? '' : initialTitle ?? '')
  const [author, setAuthor] = useState('')
  const [source, setSource] = useState<Source>(() => {
    try {
      const v = localStorage.getItem('smartlib.copycatSource')
      return v && v in SOURCES ? v as Source : 'loc'
    } catch { return 'loc' }
  })
  const [target, setTarget] = useState(() => { try { return localStorage.getItem('smartlib.z3950Target') ?? '' } catch { return '' } })
  const [result, setResult] = useState<{ target: string; total: number; records: CopyCandidate[] } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function search(e?: FormEvent) {
    e?.preventDefault()
    setBusy(true)
    setError(null)
    try {
      try {
        localStorage.setItem('smartlib.copycatSource', source)
        if (source === 'z3950') localStorage.setItem('smartlib.z3950Target', target.trim())
      } catch { /* not remembered */ }
      setResult(await api(`/api/catalog/copycat${qs({ source, target: source === 'z3950' ? target.trim() : undefined,
        ...(isbn.trim() ? { isbn } : { title, author }) })}`, { timeoutMs: 60_000 }))
    } catch (err) {
      setError(errorText(err))
      setResult(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="Copy-catalog from another library" onClose={onClose}>
      <form className="stack" onSubmit={search}>
        <div className="form-grid">
          <Field label="Search" className="span-all" hint={HINTS[source]}>
            <select value={source} onChange={(e) => { setSource(e.target.value as Source); setResult(null) }}>
              {(Object.keys(SOURCES) as Source[]).map((s) => <option key={s} value={s}>{SOURCES[s]}</option>)}
            </select>
          </Field>
          {source === 'z3950' && <Field label="Z39.50 server" className="span-all" hint="host:port/database, e.g. lx2.loc.gov:210/LCDB">
            <input className="mono" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="host:port/database" /></Field>}
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
