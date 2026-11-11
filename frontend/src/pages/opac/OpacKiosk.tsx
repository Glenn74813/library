import { useCallback, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, qs } from '../../api/client'
import { MATERIAL_TYPES, type BibRecord, type OpacDetail, type Page } from '../../api/types'
import { applyKey, OnScreenKeyboard } from '../../components/OnScreenKeyboard'
import { EbookBadge, Modal, Pager, StatusBadge, Table } from '../../components/ui'
import { fmtDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useInactivity } from '../../hooks/useInactivity'
import { Icon } from '../../components/Icon'
import { Logo } from '../../components/Logo'

/**
 * Online Public Access Catalog. Live link to the LMS catalog with real-time availability.
 * Add ?kiosk=1 on touch kiosks: large controls, on-screen keyboard, auto-reset after inactivity.
 */
export function OpacKiosk() {
  const [params] = useSearchParams()
  const kiosk = params.get('kiosk') === '1'
  const [input, setInput] = useState('')
  const [query, setQuery] = useState({ q: '', field: 'any', type: '' })
  const [field, setField] = useState('any')
  const [type, setType] = useState('')
  const [page, setPage] = useState(0)
  const [showKeys, setShowKeys] = useState(kiosk)
  const [detailId, setDetailId] = useState<number | null>(null)

  const searching = query.q !== '' || query.type !== ''
  const { data: results, loading } = useAsync(
    () => searching ? api<Page<BibRecord>>(`/api/opac/search${qs({ ...query, page })}`, { anonymous: true }) : Promise.resolve(undefined),
    [query, page])
  const { data: arrivals } = useAsync(() => api<BibRecord[]>('/api/opac/new-arrivals', { anonymous: true }), [])

  const reset = useCallback(() => {
    setInput(''); setQuery({ q: '', field: 'any', type: '' }); setField('any'); setType(''); setPage(0); setDetailId(null)
    setShowKeys(kiosk)
  }, [kiosk])
  useInactivity(kiosk ? 90_000 : 24 * 3600_000, reset)

  function search(e?: FormEvent) {
    e?.preventDefault()
    setPage(0)
    setQuery({ q: input.trim(), field, type })
  }

  const list = searching ? results?.content : arrivals

  return (
    <div className={`public-shell ${kiosk ? 'kiosk' : ''}`}>
      <div className="public-bar">
        <Logo /><span className="title">ZSCMST Library Catalog</span>
        {!kiosk && <Link className="btn small" to="/my"><Icon name="user" />My account</Link>}
        {kiosk && <button className="btn small ghost" style={{ background: 'transparent', color: 'inherit', borderColor: 'currentColor' }} onClick={reset}>Start over</button>}
      </div>
      <main className="public-main">
        <form className="opac-search" onSubmit={search} role="search">
          <input type="search" value={input} onChange={(e) => setInput(e.target.value)} placeholder="Title, author, subject, ISBN…"
            aria-label="Search the catalog" inputMode={kiosk ? 'none' : undefined} autoFocus={!kiosk} />
          <select value={field} onChange={(e) => setField(e.target.value)} aria-label="Search in">
            <option value="any">Keyword</option><option value="title">Title</option><option value="author">Author</option>
            <option value="subject">Subject</option><option value="isbn">ISBN</option><option value="callno">Call no.</option>
          </select>
          <select value={type} onChange={(e) => setType(e.target.value)} aria-label="Material type">
            <option value="">All types</option>{MATERIAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
          <button className="btn primary">Search</button>
        </form>
        {kiosk && (
          <div className="row" style={{ marginTop: 8 }}>
            <button type="button" className="btn small" onClick={() => setShowKeys((s) => !s)}><Icon name="type" />{showKeys ? 'Hide keyboard' : 'Keyboard'}</button>
          </div>
        )}
        {showKeys && <OnScreenKeyboard onKey={(k) => setInput((v) => applyKey(v, k))} onEnter={() => search()} />}

        <h2 style={{ marginTop: 20 }}>{searching ? (loading ? 'Searching…' : `Results for “${query.q || label(query.type)}”`) : 'New arrivals'}</h2>
        {list && list.length === 0 && <p className="muted">No titles found. Try fewer or different words.</p>}
        <div className="results">
          {list?.map((r) => (
            <button key={r.id} className="card result" onClick={() => setDetailId(r.id)}>
              <span className="t">{r.title}</span>
              {r.authors && <span className="muted">{r.authors}</span>}
              <span className="small muted">{[label(r.materialType), r.publicationYear, r.callNumber].filter(Boolean).join(' · ')}</span>
              {r.ebook && <span><EbookBadge ebook={r.ebook} /></span>}
              <span>{r.availableCopies > 0
                ? <span className="badge ok">{r.availableCopies} of {r.totalCopies} available</span>
                : r.totalCopies > 0 ? <span className="badge warn">All copies out</span> : <span className="badge">No copies</span>}</span>
            </button>
          ))}
        </div>
        {searching && <Pager page={results} onPage={setPage} />}
      </main>
      {detailId && <OpacDetailModal id={detailId} kiosk={kiosk} onClose={() => setDetailId(null)} />}
    </div>
  )
}

function OpacDetailModal({ id, kiosk, onClose }: { id: number; kiosk: boolean; onClose: () => void }) {
  const { data } = useAsync(() => api<OpacDetail>(`/api/opac/records/${id}`, { anonymous: true }), [id])
  const r = data?.record
  return (
    <Modal title={r?.title ?? 'Loading…'} onClose={onClose}
      footer={<>
        {!kiosk && r?.ebook?.kind === 'SCHOOL_OWNED' && <Link className="btn primary" to={`/read/${r.id}`}><Icon name="book-open" />Read e-book</Link>}
        {!kiosk && r?.ebook?.kind === 'COMMERCIAL' && <a className="btn" href={r.ebook.externalUrl!} target="_blank" rel="noopener noreferrer">
          <Icon name="external-link" />Open on {r.ebook.provider ?? 'provider'}</a>}
        {!kiosk && r && r.totalCopies > 0 && <Link className="btn primary" to={`/my?reserve=${r.id}`}>Reserve this title</Link>}
        <button className="btn" onClick={onClose}>Close</button></>}>
      {r && (
        <div className="stack">
          {r.subtitle && <p className="muted" style={{ margin: 0 }}>{r.subtitle}</p>}
          <dl className="form-grid" style={{ margin: 0 }}>
            {([['Author(s)', r.authors], ['Call number', r.callNumber], ['Publisher', r.publisher], ['Year', r.publicationYear],
              ['Edition', r.edition], ['ISBN', r.isbn], ['Type', label(r.materialType)], ['Subjects', r.subjects]] as const)
              .filter(([, v]) => v).map(([k, v]) => <div key={k}><dt className="muted small">{k}</dt><dd style={{ margin: 0 }}>{v}</dd></div>)}
          </dl>
          {r.summary && <p>{r.summary}</p>}
          <h3>Copies</h3>
          <Table rows={data!.items} rowKey={(i) => i.barcode} empty="The library has no copies yet" columns={[
            { header: 'Location', render: (i) => i.location ?? '—' },
            { header: 'Call number', render: (i) => <span className="mono">{i.callNumber ?? '—'}</span> },
            { header: 'Status', render: (i) => <><StatusBadge status={i.status} />{i.dueAt && <span className="small muted"> due {fmtDate(i.dueAt)}</span>}</> },
          ]} />
          {kiosk && <p className="muted small">To reserve{r.ebook ? ' or read this e-book' : ''}, sign in to My Library on your phone or ask at the circulation desk.</p>}
          {!kiosk && r.ebook?.kind === 'SCHOOL_OWNED' && <p className="muted small" style={{ margin: 0 }}>School-owned e-book: free to read for signed-in patrons.</p>}
        </div>
      )}
    </Modal>
  )
}
