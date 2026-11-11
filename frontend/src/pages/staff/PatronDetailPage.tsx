import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { BibRecord, Fine, Loan, Page, PatronSummary, Receipt } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { PatronFormModal } from '../../components/PatronForm'
import { BookModal } from '../../components/BookModal'
import { ReceiptModal } from '../../components/ReceiptModal'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table, Tabs } from '../../components/ui'
import { fmtDate, fmtMoney } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'

type Tab = 'loans' | 'history' | 'holds' | 'fines'

export function PatronDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const isLibrarian = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [tab, setTab] = useState<Tab>('loans')
  const [editing, setEditing] = useState(false)
  const [pinOpen, setPinOpen] = useState(false)
  const [holdOpen, setHoldOpen] = useState(false)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [selected, setSelected] = useState<number[]>([])
  const [histPage, setHistPage] = useState(0)
  const { data: s, error, reload } = useAsync(() => api<PatronSummary>(`/api/circulation/patrons/${id}/summary`), [id])
  const { data: fines, reload: reloadFines } = useAsync(() => api<Fine[]>(`/api/fines/patron/${id}`), [id])
  const { data: history } = useAsync(
    () => tab === 'history' ? api<Page<Loan>>(`/api/circulation/patrons/${id}/history${qs({ page: histPage })}`) : Promise.resolve(undefined),
    [id, tab, histPage])

  const refreshAll = () => { void reload(); void reloadFines(); setSelected([]) }

  async function act(fn: () => Promise<unknown>, ok: string) {
    try {
      await fn()
      toast(ok)
      refreshAll()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function paySelected() {
    try {
      setReceipt(await api<Receipt>('/api/fines/pay', { body: { fineIds: selected } }))
      refreshAll()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function addFine() {
    const reason = prompt('Charge reason (e.g. Damaged book cover):')
    if (!reason) return
    const amount = Number(prompt('Amount in PHP:'))
    if (!amount || amount <= 0) return
    await act(() => api('/api/fines', { body: { patronId: Number(id), amount, reason } }), 'Charge added')
  }

  async function markLost(l: Loan) {
    const charge = prompt(`Mark "${l.title}" as LOST. Replacement charge (PHP):`, '0')
    if (charge === null) return
    await act(() => api(`/api/circulation/loans/${l.id}/lost`, { body: { charge: Number(charge) || 0 } }), 'Item marked lost')
  }

  if (error) return <ErrorAlert error={error} />
  if (!s) return <p className="muted">Loading…</p>
  const p = s.patron
  const unpaid = fines?.filter((f) => f.status === 'UNPAID') ?? []
  const selectedTotal = unpaid.filter((f) => selected.includes(f.id)).reduce((a, f) => a + f.amount, 0)

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ flexWrap: 'nowrap', minWidth: 0 }}>
          {p.photoUrl && <img src={p.photoUrl} alt="" width={56} height={56} style={{ borderRadius: 8, objectFit: 'cover' }} />}
          <div style={{ minWidth: 0 }}>
            <h1>{p.fullName}</h1>
            <div className="muted small">
              <span className="mono">{p.cardNumber}</span> · {p.categoryName}{p.courseCode && ` · ${p.courseCode}`}
              {p.expiryDate && ` · expires ${fmtDate(p.expiryDate)}`}
            </div>
          </div>
        </div>
        <div className="row">
          <button className="btn" onClick={() => navigate('/staff/patrons')}><Icon name="chevron-left" />Back</button>
          <button className="btn" onClick={() => setPinOpen(true)}>{p.hasPin ? 'Reset PIN' : 'Set portal PIN'}</button>
          <button className="btn" onClick={() => setHoldOpen(true)}>Reserve / book</button>
          <button className="btn primary" onClick={() => setEditing(true)}>Edit</button>
        </div>
      </div>

      {s.blocks.length > 0 && <div className="alert warn" style={{ marginBottom: 16 }}><ul style={{ margin: 0 }}>{s.blocks.map((b) => <li key={b}>{b}</li>)}</ul></div>}
      <div className="stats" style={{ marginBottom: 16 }}>
        <div className="stat"><div className="label">On loan</div><div className="value">{s.loans.length} / {s.maxLoans}</div></div>
        <div className="stat"><div className="label">Holds</div><div className="value">{s.holds.filter((h) => h.status === 'PENDING' || h.status === 'READY').length}</div></div>
        <div className={`stat ${s.unpaidTotal > 0 ? 'bad' : ''}`}><div className="label">Unpaid fines</div><div className="value">{fmtMoney(s.unpaidTotal)}</div></div>
        <div className="stat"><div className="label">Contact</div><div className="small">{p.email ?? '—'}<br />{p.phone ?? ''}</div></div>
      </div>

      <Tabs value={tab} onChange={setTab} tabs={[
        { id: 'loans', label: `Loans (${s.loans.length})` }, { id: 'history', label: 'History' },
        { id: 'holds', label: `Holds (${s.holds.length})` }, { id: 'fines', label: `Fines (${unpaid.length})` }]} />

      <div className="card">
        {tab === 'loans' && <Table rows={s.loans} rowKey={(l) => l.id} empty="No items on loan" columns={[
          { header: 'Title', render: (l) => <>{l.title}<div className="muted small mono">{l.barcode}</div></> },
          { header: 'Borrowed', render: (l) => fmtDate(l.checkoutAt) },
          { header: 'Due', render: (l) => <span className="nowrap">{fmtDate(l.dueAt)} {l.overdue && <span className="badge bad">{l.overdueDays}d late</span>}</span> },
          { header: 'Renewed', render: (l) => `${l.renewals}/${l.maxRenewals}` },
          { header: '', render: (l) => <div className="row">
            <button className="btn small" onClick={() => act(() => api(`/api/circulation/loans/${l.id}/renew`, { method: 'POST' }), 'Renewed')}>Renew</button>
            {isLibrarian && <button className="btn small danger" onClick={() => markLost(l)}>Lost</button>}
          </div> },
        ]} />}

        {tab === 'history' && <>
          <Table rows={history?.content} rowKey={(l) => l.id} empty="No borrowing history" columns={[
            { header: 'Title', render: (l) => l.title },
            { header: 'Borrowed', render: (l) => fmtDate(l.checkoutAt) },
            { header: 'Due', render: (l) => fmtDate(l.dueAt) },
            { header: 'Returned', render: (l) => l.returnedAt ? fmtDate(l.returnedAt) : <span className="badge info">On loan</span> },
          ]} />
          <Pager page={history} onPage={setHistPage} />
        </>}

        {tab === 'holds' && <Table rows={s.holds} rowKey={(h) => h.id} empty="No holds" columns={[
          { header: 'Title', render: (h) => h.title },
          { header: 'Status', render: (h) => <StatusBadge status={h.status} /> },
          { header: 'Placed', render: (h) => fmtDate(h.placedAt) },
          { header: 'Info', render: (h) => h.status === 'READY' ? `Pick up by ${fmtDate(h.expiresAt)}`
            : h.status === 'SCHEDULED' ? `Pickup on ${fmtDate(h.pickupDate)}`
            : h.queuePosition ? `Queue #${h.queuePosition}` : '—' },
          { header: '', render: (h) => (h.status === 'PENDING' || h.status === 'READY' || h.status === 'SCHEDULED') &&
            <button className="btn small danger" onClick={() => act(() => api(`/api/holds/${h.id}`, { method: 'DELETE' }), 'Hold cancelled')}>Cancel</button> },
        ]} />}

        {tab === 'fines' && <div className="stack">
          <div className="row">
            <button className="btn primary" disabled={!selected.length} onClick={paySelected}>
              Receive {selected.length ? fmtMoney(selectedTotal) : 'payment'}</button>
            {unpaid.length > 0 && <button className="btn" onClick={() => setSelected(unpaid.map((f) => f.id))}>Select all unpaid</button>}
            <span className="spacer" />
            {isLibrarian && <button className="btn" onClick={addFine}><Icon name="plus" />Add charge</button>}
          </div>
          <Table rows={fines} rowKey={(f) => f.id} empty="No fines" columns={[
            { header: '', render: (f) => f.status === 'UNPAID' && <input type="checkbox" aria-label="Select fine" checked={selected.includes(f.id)}
              onChange={(e) => setSelected((sel) => e.target.checked ? [...sel, f.id] : sel.filter((x) => x !== f.id))} /> },
            { header: 'Date', render: (f) => fmtDate(f.createdAt) },
            { header: 'Reason', render: (f) => f.reason },
            { header: 'Amount', render: (f) => fmtMoney(f.amount) },
            { header: 'Status', render: (f) => <StatusBadge status={f.status} /> },
            { header: 'Receipt', render: (f) => f.receiptNumber
              ? <button className="btn small" onClick={async () => setReceipt(await api<Receipt>(`/api/fines/receipts/${f.receiptNumber}`))}>{f.receiptNumber}</button> : '—' },
          ]} />
        </div>}
      </div>

      {editing && <PatronFormModal patron={p} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); refreshAll() }} />}
      {pinOpen && <PinModal patronId={p.id} onClose={() => setPinOpen(false)} onSaved={() => { setPinOpen(false); refreshAll() }} />}
      {holdOpen && <HoldModal patronId={p.id} onClose={() => setHoldOpen(false)} onSaved={() => { setHoldOpen(false); refreshAll(); setTab('holds') }} />}
      {receipt && <ReceiptModal receipt={receipt} onClose={() => setReceipt(null)} />}
    </>
  )
}

function PinModal({ patronId, onClose, onSaved }: { patronId: number; onClose: () => void; onSaved: () => void }) {
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string | null>(null)
  async function save() {
    try {
      await api(`/api/patrons/${patronId}/pin`, { body: { pin } })
      onSaved()
    } catch (e) {
      setError(errorText(e))
    }
  }
  return (
    <Modal title="Patron portal PIN" onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save PIN</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <p className="muted small">The patron signs in to My Library with their card number and this PIN.</p>
        <Field label="New PIN (4–8 digits)"><input inputMode="numeric" pattern="\d*" maxLength={8} value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} autoFocus /></Field>
      </div>
    </Modal>
  )
}

function HoldModal({ patronId, onClose, onSaved }: { patronId: number; onClose: () => void; onSaved: () => void }) {
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const toast = useToast()
  const { data } = useAsync(() => dq ? api<Page<BibRecord>>(`/api/catalog/records${qs({ q: dq, size: 10 })}`) : Promise.resolve(undefined), [dq])
  const [busy, setBusy] = useState(false)
  const [booking, setBooking] = useState<BibRecord | null>(null)
  async function place(r: BibRecord) {
    if (busy) return
    setBusy(true)
    try {
      await api('/api/holds', { body: { patronId, recordId: r.id } })
      toast('Hold placed')
      onSaved()
    } catch (e) {
      toast(errorText(e), 'error')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal title="Reserve or book a title" onClose={onClose}>
      <div className="stack">
        <input type="search" placeholder="Search title…" value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
        {data && <Table rows={data.content} rowKey={(r) => r.id} empty="No matches" columns={[
          { header: 'Title', render: (r) => <>{r.title}<div className="muted small">{r.authors}</div></> },
          { header: 'Available', render: (r) => `${r.availableCopies}/${r.totalCopies}` },
          { header: '', render: (r) => <span className="row">
            <button className="btn small primary" disabled={busy} onClick={() => place(r)}>Reserve</button>
            <button className="btn small" onClick={() => setBooking(r)}>Book a date</button>
          </span> },
        ]} />}
      </div>
      {booking && <BookModal title={booking.title} calendarPath={`/api/holds/calendar?recordId=${booking.id}&patronId=${patronId}`}
        onClose={() => setBooking(null)}
        onBook={async (pickupDate) => {
          await api('/api/holds', { body: { patronId, recordId: booking.id, pickupDate } })
          toast(`Booked for ${fmtDate(pickupDate)}`)
          setBooking(null)
          onSaved()
        }} />}
    </Modal>
  )
}
