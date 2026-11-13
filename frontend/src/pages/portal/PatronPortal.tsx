import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { api, download, logout, openPdf, qs } from '../../api/client'
import { useLibrary } from '../../api/library'
import type { BibRecord, Ebook, Fine, Hold, OpacDetail, Page, Patron, PatronSummary, RenewalRequest } from '../../api/types'
import { BookModal } from '../../components/BookModal'
import { PatronPhotoModal } from '../../components/PatronPhotoModal'
import { hasRole, usePatronSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { EbookBadge, ErrorAlert, Field, Pager, Stat, StatusBadge, Table, Tabs, PasswordInput } from '../../components/ui'
import { fmtBytes, fmtDate, fmtMoney } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'
import { Logo } from '../../components/Logo'

type Tab = 'loans' | 'holds' | 'ebooks' | 'fines' | 'search' | 'account'

/** Patron self-service portal - the "mobile app" as a responsive web app. */
export function PatronPortal() {
  const session = usePatronSession()
  const [params, setParams] = useSearchParams()
  const [tab, setTab] = useState<Tab>('loans')
  const toast = useToast()
  const navigate = useNavigate()
  const isPatron = hasRole(session, 'PATRON')
  const { data: me, error, reload } = useAsync(
    () => isPatron ? api<PatronSummary>('/api/me') : Promise.resolve(undefined), [isPatron])

  const { data: renewals, reload: reloadRenewals } = useAsync(
    () => isPatron ? api<RenewalRequest[]>('/api/me/renewals') : Promise.resolve(undefined), [isPatron])
  const pendingLoans = new Set((renewals ?? []).filter((r) => r.status === 'PENDING').map((r) => r.loanId))
  const handledReserve = useRef<string | null>(null)
  const [booking, setBooking] = useState<{ id: number; title: string } | null>(null)

  // Arrived from the OPAC "Reserve" button.
  useEffect(() => {
    const reserve = params.get('reserve')
    // Handle each ?reserve= link once, even if the effect runs again before the URL is cleared.
    if (!isPatron || !reserve || handledReserve.current === reserve) return
    handledReserve.current = reserve
    setParams({}, { replace: true })
    api('/api/me/holds', { body: { recordId: Number(reserve) } })
      .then(() => { toast('Title reserved — we will notify you when it is ready'); setTab('holds'); void reload() })
      .catch((e) => toast(errorText(e), 'error'))
  }, [isPatron]) // eslint-disable-line react-hooks/exhaustive-deps

  // Arrived from the OPAC "Book a date" button: open the date picker for that title.
  useEffect(() => {
    const book = params.get('book')
    if (!isPatron || !book) return
    setParams({}, { replace: true })
    api<OpacDetail>(`/api/opac/records/${book}`, { anonymous: true })
      .then((d) => setBooking({ id: d.record.id, title: d.record.title }))
      .catch((e) => toast(errorText(e), 'error'))
  }, [isPatron]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!isPatron) {
    const link = params.get('reserve') ? `/my?reserve=${params.get('reserve')}` : params.get('book') ? `/my?book=${params.get('book')}` : null
    return <Navigate to={`/my/login${link ? `?next=${encodeURIComponent(link)}` : ''}`} replace />
  }

  async function book(recordId: number, pickupDate: string) {
    await api('/api/me/holds', { body: { recordId, pickupDate } })
    toast(`Booked for ${fmtDate(pickupDate)} — we will have it ready that morning`)
    setBooking(null)
    setTab('holds')
    void reload()
  }

  async function renew(loanId: number) {
    try {
      const r = await api<{ pending: boolean; message: string }>(`/api/me/loans/${loanId}/renew`, { method: 'POST' })
      toast(r.pending ? 'Renewal requested. The library will approve or reject it.' : 'Renewed!')
      void reload(); void reloadRenewals()
    } catch (e) { toast(errorText(e), 'error') }
  }



  return (
    <div className="public-shell">
      <div className="public-bar">
        <Logo /><span className="title">My Library</span>
        <Link className="btn small" to="/my/catalog"><Icon name="search" />Catalog</Link>
        <button className="btn small" onClick={async () => { await logout(); navigate('/my/login') }}><Icon name="log-out" />Sign out</button>
      </div>
      <main className="public-main">
        <ErrorAlert error={error} />
        {me && <>
          <h1 style={{ marginBottom: 4 }}>Hi, {me.patron.firstName}!</h1>
          <p className="muted" style={{ marginTop: 0 }}>{me.patron.cardNumber} · {me.patron.categoryName}{me.patron.courseCode && ` · ${me.patron.courseCode}`}</p>
          {me.blocks.length > 0 && <div className="alert warn" style={{ marginBottom: 12 }}><ul style={{ margin: 0 }}>{me.blocks.map((b) => <li key={b}>{b}</li>)}</ul></div>}
          <div className="stats" style={{ marginBottom: 16 }}>
            <Stat label="Borrowed" value={`${me.loans.length}/${me.maxLoans}`} />
            <Stat label="Overdue" value={me.loans.filter((l) => l.overdue).length} tone={me.loans.some((l) => l.overdue) ? 'bad' : undefined} />
            <Stat label="Ready for pickup" value={me.holds.filter((h) => h.status === 'READY').length} />
            <Stat label="Fines due" value={fmtMoney(me.unpaidTotal)} tone={me.unpaidTotal > 0 ? 'bad' : undefined} />
          </div>
          <Tabs value={tab} onChange={setTab} tabs={[
            { id: 'loans', label: 'My loans' }, { id: 'holds', label: 'Reservations' }, { id: 'ebooks', label: 'E-books' },
            { id: 'fines', label: 'Fines' },
            { id: 'search', label: 'Search & reserve' }, { id: 'account', label: 'My library ID' }]} />

          {tab === 'loans' && <div className="stack">
            <div className="card">
              <Table rows={me.loans} rowKey={(l) => l.id} empty="You have no borrowed items" columns={[
                { header: 'Title', render: (l) => <strong>{l.title}</strong> },
                { header: 'Due', render: (l) => <span className="nowrap">{fmtDate(l.dueAt)} {l.overdue && <span className="badge bad">Overdue</span>}</span> },
                { header: 'Renewals', render: (l) => `${l.renewals} of ${l.maxRenewals}` },
                { header: 'Actions', render: (l) => pendingLoans.has(l.id)
                  ? <span className="badge info">Renewal requested</span>
                  : <button className="btn small primary" disabled={l.overdue || l.renewals >= l.maxRenewals} onClick={() => renew(l.id)}><Icon name="refresh-cw" />Renew</button> },
              ]} />
            </div>
            {renewals && renewals.length > 0 && <div className="card">
              <h2 style={{ marginTop: 0 }}>Renewal requests</h2>
              <Table rows={renewals} rowKey={(r) => r.id} columns={[
                { header: 'Title', render: (r) => r.title },
                { header: 'Asked', render: (r) => fmtDate(r.requestedAt) },
                { header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
                { header: 'Details', render: (r) => r.status === 'PENDING' ? 'Waiting for the library — keep the item until you hear back'
                  : r.status === 'APPROVED' ? `Now due ${fmtDate(r.newDueAt)}`
                  : r.status === 'REJECTED' ? <>Reason: <strong>{r.reason}</strong> · return by {fmtDate(r.dueAt)}</>
                  : r.reason ?? '—' },
              ]} />
            </div>}
          </div>}

          {tab === 'holds' && <HoldsTab onChange={reload} />}
          {tab === 'ebooks' && <EbooksTab />}
          {tab === 'fines' && <FinesTab />}
          {tab === 'search' && <SearchTab onReserved={() => { setTab('holds'); void reload() }} onBook={(r) => setBooking({ id: r.id, title: r.title })} />}
          {tab === 'account' && <AccountTab patron={me.patron} onChanged={() => void reload()} />}
        </>}
        {booking && <BookModal title={booking.title} calendarPath={`/api/me/holds/calendar/${booking.id}`}
          onBook={(d) => book(booking.id, d)} onClose={() => setBooking(null)} />}
      </main>
    </div>
  )
}

function HoldsTab({ onChange }: { onChange: () => void }) {
  const { data, reload } = useAsync(() => api<Hold[]>('/api/me/holds'), [])
  const toast = useToast()
  async function cancel(h: Hold) {
    if (!confirm(`Cancel your reservation for "${h.title}"?`)) return
    try { await api(`/api/me/holds/${h.id}`, { method: 'DELETE' }); toast('Reservation cancelled'); void reload(); onChange() }
    catch (e) { toast(errorText(e), 'error') }
  }
  return (
    <div className="card">
      <Table rows={data} rowKey={(h) => h.id} empty="No reservations" columns={[
        { header: 'Title', render: (h) => <strong>{h.title}</strong> },
        { header: 'Status', render: (h) => <StatusBadge status={h.status} /> },
        { header: 'Info', render: (h) => h.status === 'READY' ? `Pick up by ${fmtDate(h.expiresAt)}`
          : h.status === 'SCHEDULED' ? `Pickup on ${fmtDate(h.pickupDate)}`
          : h.queuePosition ? `#${h.queuePosition} in line` : fmtDate(h.placedAt) },
        { header: 'Actions', render: (h) => (h.status === 'PENDING' || h.status === 'READY' || h.status === 'SCHEDULED') && <button className="btn small danger" onClick={() => cancel(h)}>Cancel</button> },
      ]} />
    </div>
  )
}

function EbooksTab() {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const { data, error } = useAsync(() => api<Page<Ebook>>(`/api/me/ebooks${qs({ q, page })}`), [q, page])
  return (
    <div className="card stack">
      <p className="muted small" style={{ margin: 0 }}>
        <strong>School-owned</strong> e-books open right here on any device. <strong>Commercial</strong> e-books open on the
        provider's platform, where you may need that service's own account.
      </p>
      <form className="input-group" onSubmit={(e: FormEvent) => { e.preventDefault(); setPage(0); setQ(input.trim()) }}>
        <input type="search" value={input} onChange={(e) => setInput(e.target.value)} placeholder="Search e-books by title or author…" />
        <button className="btn primary">Search</button>
      </form>
      <ErrorAlert error={error} />
      <Table rows={data?.content} rowKey={(e) => e.recordId} empty="No e-books found" columns={[
        { header: 'Title', render: (e) => <><strong>{e.title}</strong><div className="muted small">{[e.authors, e.publicationYear].filter(Boolean).join(' · ')}</div></> },
        { header: 'Type', render: (e) => <EbookBadge ebook={{ kind: e.kind, format: e.format, provider: e.provider, externalUrl: e.externalUrl }} /> },
        { header: 'Size', render: (e) => e.kind === 'SCHOOL_OWNED' ? fmtBytes(e.sizeBytes) : '—' },
        { header: 'Actions', render: (e) => e.kind === 'SCHOOL_OWNED'
          ? <Link className="btn small primary" to={`/read/${e.recordId}`}><Icon name="book-open" />Read</Link>
          : <a className="btn small" href={e.externalUrl!} target="_blank" rel="noopener noreferrer"><Icon name="external-link" />Open on {e.provider ?? 'provider'}</a> },
      ]} />
      <Pager page={data} onPage={setPage} />
    </div>
  )
}

function FinesTab() {
  const { data } = useAsync(() => api<Fine[]>('/api/me/fines'), [])
  return (
    <div className="card">
      <p className="muted small" style={{ marginTop: 0 }}>Pay fines at the circulation desk. The printed payment receipt is not an official receipt; ask the library for one.</p>
      <Table rows={data} rowKey={(f) => f.id} empty="No fines" columns={[
        { header: 'Date', render: (f) => fmtDate(f.createdAt) },
        { header: 'Reason', render: (f) => f.reason },
        { header: 'Amount', render: (f) => fmtMoney(f.amount) },
        { header: 'Status', render: (f) => <StatusBadge status={f.status} /> },
      ]} />
    </div>
  )
}

function SearchTab({ onReserved, onBook }: { onReserved: () => void; onBook: (r: BibRecord) => void }) {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const toast = useToast()
  const { data } = useAsync(() => q ? api<Page<BibRecord>>(`/api/opac/search${qs({ q, size: 20 })}`, { anonymous: true }) : Promise.resolve(undefined), [q])
  const [busy, setBusy] = useState<number | null>(null)
  async function reserve(r: BibRecord) {
    if (busy !== null) return
    setBusy(r.id)
    try { await api('/api/me/holds', { body: { recordId: r.id } }); toast('Reserved!'); onReserved() }
    catch (e) { toast(errorText(e), 'error') }
    finally { setBusy(null) }
  }
  return (
    <div className="card stack">
      <form className="input-group" onSubmit={(e: FormEvent) => { e.preventDefault(); setQ(input.trim()) }}>
        <input type="search" value={input} onChange={(e) => setInput(e.target.value)} placeholder="Title, author, subject…" />
        <button className="btn primary">Search</button>
      </form>
      {data && <Table rows={data.content} rowKey={(r) => r.id} empty="No titles found" columns={[
        { header: 'Title', render: (r) => <><strong>{r.title}</strong><div className="muted small">{r.authors}</div></> },
        { header: 'Available', render: (r) => <span className={`badge ${r.availableCopies ? 'ok' : 'warn'}`}>{r.availableCopies}/{r.totalCopies}</span> },
        { header: 'Actions', render: (r) => r.totalCopies > 0 && <span className="row-actions">
          <button className="btn small primary" disabled={busy !== null} onClick={() => reserve(r)}>{busy === r.id ? 'Reserving…' : 'Reserve'}</button>
          <button className="btn small" onClick={() => onBook(r)}>Book a date</button>
        </span> },
      ]} />}
    </div>
  )
}

/**
 * The patron's own library ID: shown on screen, printable at home as a marked patron copy (the server only
 * ever returns the signed-in patron's own ID), and the photo used on it.
 */
function MyLibraryId({ patron, onChanged }: { patron: Patron; onChanged: () => void }) {
  const toast = useToast()
  const lib = useLibrary()
  const [open, setOpen] = useState(false)
  const expired = !!patron.expiryDate && patron.expiryDate < new Date().toISOString().slice(0, 10)
  return (
    <div className="card stack" style={{ maxWidth: 420 }}>
      <h2>My library ID</h2>
      <div className="id-preview" aria-label="Your library ID">
        <div className="id-head"><strong>{lib.name}</strong><span>LIBRARY ID</span></div>
        <div className="id-body">
          {patron.photoUrl ? <img src={patron.photoUrl} alt="Your ID photo" />
            : <div className="id-photo-empty"><Icon name="user" size={32} /></div>}
          <div className="stack" style={{ gap: 4, minWidth: 0 }}>
            <strong className="id-name">{patron.lastName.toUpperCase()}, {patron.firstName}</strong>
            <div><span className="muted small">ID NO.</span><div className="mono"><strong>{patron.cardNumber}</strong></div></div>
            <div><span className="muted small">TYPE</span><div>{patron.categoryName}{patron.courseCode && ` · ${patron.courseCode}`}</div></div>
            {patron.expiryDate && <div><span className="muted small">VALID UNTIL</span><div>{fmtDate(patron.expiryDate)}</div></div>}
          </div>
        </div>
      </div>
      {(expired || patron.blocked) && <div className="alert warn small">
        {expired ? 'Your library ID has expired. ' : ''}{patron.blocked ? 'Your account is on hold. ' : ''}Please see the circulation desk.</div>}
      <button type="button" className="btn primary" onClick={() => openPdf('/api/me/library-id', 'patron')
        .catch((e) => toast(errorText(e), 'error'))}><Icon name="printer" />View / print my library ID</button>
      <p className="muted small" style={{ margin: 0 }}>The printout is marked <strong>patron copy</strong>. Your official library ID is issued
        at the circulation desk. Keep your PIN to yourself: it, not the ID, protects your account.</p>
      <hr style={{ width: '100%', border: 0, borderTop: '1px solid var(--border)' }} />
      <h3 style={{ margin: 0 }}>Photo</h3>
      <p className="muted small" style={{ margin: 0 }}>Used on your library ID. Face the camera, plain background, no sunglasses or hat.
        A JPEG or PNG works best.</p>
      <button type="button" className="btn" onClick={() => setOpen(true)}><Icon name="camera" />{patron.photoUrl ? 'Change photo' : 'Add a photo'}</button>
      {open && <PatronPhotoModal patron={patron} endpoint="/api/me/photo" title="Your library ID photo"
        onClose={() => setOpen(false)} onSaved={() => { setOpen(false); toast('Photo saved for your library ID'); onChanged() }} />}
    </div>
  )
}

function AccountTab({ patron, onChanged }: { patron: Patron; onChanged: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const toast = useToast()
  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await api('/api/me/pin', { body: { currentPin: current, newPin: next } })
      toast('PIN changed. Please sign in again.')
      await logout()
    } catch (err) { toast(errorText(err), 'error') }
  }
  return (
    <div className="grid cols-2" style={{ alignItems: 'start' }}>
    <MyLibraryId patron={patron} onChanged={onChanged} />
    <form className="card stack" style={{ maxWidth: 420 }} onSubmit={submit}>
      <h2>Change PIN</h2>
      <Field label="Current PIN"><PasswordInput inputMode="numeric" value={current} onChange={(e) => setCurrent(e.target.value)} required /></Field>
      <Field label="New PIN (4–8 digits)"><PasswordInput inputMode="numeric" pattern="\d{4,8}" value={next} onChange={(e) => setNext(e.target.value)} required /></Field>
      <button className="btn primary">Change PIN</button>
      <hr style={{ width: '100%', border: 0, borderTop: '1px solid var(--border)' }} />
      <h2>Your data</h2>
      <p className="muted small" style={{ margin: 0 }}>Download a copy of everything the library keeps about you: profile, loans, fines,
        reservations, visits and notices. To correct or erase your data, ask the library. <Link to="/privacy">Privacy notice</Link></p>
      <button type="button" className="btn" onClick={() => download('/api/me/data-export', 'my-library-data.json')
        .catch((e) => toast(errorText(e), 'error'))}><Icon name="download" />Download my data</button>
    </form>
    </div>
  )
}
