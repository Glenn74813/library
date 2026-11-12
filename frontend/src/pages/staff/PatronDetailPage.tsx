import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, download, openPdf, qs, releaseHold } from '../../api/client'
import type { BibRecord, Fine, Hold, Loan, Page, Patron, PatronSummary, Receipt } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { PatronFormModal } from '../../components/PatronForm'
import { BookModal } from '../../components/BookModal'
import { PatronPhotoModal } from '../../components/PatronPhotoModal'
import { ReceiptModal } from '../../components/ReceiptModal'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table, Tabs } from '../../components/ui'
import { fmtDate, fmtDateTime, fmtMoney } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { Icon } from '../../components/Icon'
import { useLibrary } from '../../api/library'
import { ClearanceSlip, PatronSlip, printSlip } from '../../print/slips'

type Tab = 'loans' | 'history' | 'holds' | 'fines' | 'attendance'

export function PatronDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const isLibrarian = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [tab, setTab] = useState<Tab>('loans')
  const [editing, setEditing] = useState(false)
  const [pinOpen, setPinOpen] = useState(false)
  const [holdOpen, setHoldOpen] = useState(false)
  const [photoOpen, setPhotoOpen] = useState(false)
  const [erasing, setErasing] = useState(false)
  const [clearanceOpen, setClearanceOpen] = useState(false)
  const lib = useLibrary()
  const [params, setParams] = useSearchParams()
  const justRegistered = params.get('new') === '1'
  const isAdmin = hasRole(useSession(), 'ADMIN')
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

  async function release(h: Hold) {
    if (!confirm(`Check out "${h.title}" (copy ${h.itemBarcode}) to ${h.patronName}?`)) return
    try {
      const r = await releaseHold(h)
      toast(`Released "${h.title}" to ${h.patronName} — due ${fmtDate(r.loan.dueAt)}${r.notice ? ` (${r.notice})` : ''}`)
      refreshAll()
      setTab('loans')
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function cancelHold(h: Hold) {
    if (!confirm(`Cancel hold of ${h.patronName} on "${h.title}"?`)) return
    await act(() => api(`/api/holds/${h.id}`, { method: 'DELETE' }), 'Hold cancelled')
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
    const defaults = await api<{ defaultLostFee: number }>('/api/circulation/settings').catch(() => ({ defaultLostFee: 0 }))
    const charge = prompt(`Mark "${l.title}" as LOST. Replacement charge (PHP):`, String(defaults.defaultLostFee ?? 0))
    if (charge === null) return
    await act(() => api(`/api/circulation/loans/${l.id}/lost`, { body: { charge: Number(charge) || 0 } }), 'Item marked lost')
  }

  if (error) return <ErrorAlert error={error} />
  if (!s) return <p className="muted">Loading…</p>
  const p = s.patron
  const unpaid = fines?.filter((f) => f.status === 'UNPAID') ?? []
  const ready = s.holds.filter((h) => h.status === 'READY')
  const waiting = s.holds.filter((h) => h.status === 'PENDING')
  const booked = s.holds.filter((h) => h.status === 'SCHEDULED').sort((a, b) => (a.pickupDate ?? '').localeCompare(b.pickupDate ?? ''))
  const activeHolds = [...ready, ...waiting, ...booked]
  const pastHolds = s.holds.filter((h) => !activeHolds.includes(h))
  const holdCols = [
    { header: 'Title', render: (h: Hold) => h.title },
    { header: 'Status', render: (h: Hold) => <span className="row" style={{ gap: 4 }}><StatusBadge status={h.status} />
      {h.atRisk && <span className="badge bad" title="No copy on the shelf or due back in time">At risk</span>}</span> },
    { header: 'Placed', render: (h: Hold) => fmtDateTime(h.placedAt) },
    { header: 'Details', render: (h: Hold) => h.status === 'SCHEDULED'
      ? <>Pickup on <strong>{fmtDate(h.pickupDate)}</strong> · copy set aside that morning</>
      : h.status === 'READY'
      ? <>Copy <span className="mono">{h.itemBarcode}</span>{h.itemShelfLocation && <> from <strong>{h.itemShelfLocation}</strong></>} · pick up by {fmtDate(h.expiresAt)}</>
      : h.pickupDate ? <>Booked for <strong>{fmtDate(h.pickupDate)}</strong> · first in line for the next returned copy</>
      : `Queue #${h.queuePosition}` },
    { header: '', render: (h: Hold) => <span className="row-actions">
      {h.status === 'READY' && <button className="btn small primary" onClick={() => release(h)}>Release (check out)</button>}
      <button className="btn small danger" onClick={() => cancelHold(h)}>Cancel</button>
    </span> },
  ]
  const selectedTotal = unpaid.filter((f) => selected.includes(f.id)).reduce((a, f) => a + f.amount, 0)

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ flexWrap: 'nowrap', minWidth: 0 }}>
          <button className="avatar" title="Photo" onClick={() => setPhotoOpen(true)}>
            {p.photoUrl ? <img src={p.photoUrl} alt="" className="avatar" /> : <Icon name="camera" size={22} />}
          </button>
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
          <select className="btn" style={{ width: 'auto' }} value="" aria-label="Print" onChange={(e) => {
            const v = e.target.value
            if (v === 'card') openPdf(`/api/labels/patron-cards${qs({ cards: p.cardNumber })}`).catch((err) => toast(errorText(err), 'error'))
            if (v === 'welcome') void printSlip(<PatronSlip lib={lib} patron={p} />)
            if (v === 'clearance') setClearanceOpen(true)
          }}>
            <option value="" disabled>Print…</option>
            <option value="welcome">Card slip (receipt printer)</option>
            <option value="clearance">Library clearance…</option>
            <option value="card">ID card (PDF)</option>
          </select>
          <button className="btn" onClick={() => setHoldOpen(true)}>Reserve / book</button>
          <select className="btn" style={{ width: 'auto' }} value="" aria-label="Data privacy" onChange={(e) => {
            if (e.target.value === 'export') void download(`/api/patrons/${p.id}/data-export`, 'patron-data.json')
            if (e.target.value === 'erase') setErasing(true)
          }}>
            <option value="" disabled>Data privacy…</option>
            <option value="export">Download patron's data (JSON)</option>
            {isAdmin && !p.anonymizedAt && <option value="erase">Anonymize / erase…</option>}
          </select>
          <button className="btn primary" onClick={() => setEditing(true)}>Edit</button>
        </div>
      </div>
      {justRegistered && <div className="alert success row" style={{ marginBottom: 16, justifyContent: 'space-between' }}>
        <span><strong>Patron registered.</strong> Give them a card slip with their card number and the My Library address
          {p.hasPin ? '' : ', and set a PIN so they can sign in'}.</span>
        <span className="row-actions">
          <button className="btn small" onClick={() => void printSlip(<PatronSlip lib={lib} patron={p} />)}><Icon name="printer" />Print card slip</button>
          {!p.hasPin && <button className="btn small" onClick={() => setPinOpen(true)}>Set PIN</button>}
          <button className="btn small" onClick={() => setParams({}, { replace: true })}>Done</button>
        </span>
      </div>}
      {p.anonymizedAt && <div className="alert" style={{ marginBottom: 16 }}>This patron was anonymized on {fmtDate(p.anonymizedAt)} under the Data Privacy Act. Their loan and fine history is kept for statistics only.</div>}

      {s.blocks.length > 0 && <div className="alert warn" style={{ marginBottom: 16 }}><ul style={{ margin: 0 }}>{s.blocks.map((b) => <li key={b}>{b}</li>)}</ul></div>}
      <div className="stats" style={{ marginBottom: 16 }}>
        <div className="stat"><div className="label">On loan</div><div className="value">{s.loans.length} / {s.maxLoans}</div></div>
        <div className="stat"><div className="label">Holds</div><div className="value">{activeHolds.length}</div></div>
        <div className={`stat ${s.unpaidTotal > 0 ? 'bad' : ''}`}><div className="label">Unpaid fines</div><div className="value">{fmtMoney(s.unpaidTotal)}</div></div>
        <div className="stat"><div className="label">Contact</div><div className="small">{p.email ?? '—'}<br />{p.phone ?? ''}</div></div>
      </div>

      <Tabs value={tab} onChange={setTab} tabs={[
        { id: 'loans', label: `Loans (${s.loans.length})` }, { id: 'history', label: 'History' },
        { id: 'holds', label: `Holds (${activeHolds.length})` }, { id: 'fines', label: `Fines (${unpaid.length})` }, { id: 'attendance', label: 'Attendance' }]} />

      <div className="card">
        {tab === 'loans' && <Table rows={s.loans} rowKey={(l) => l.id} empty="No items on loan" columns={[
          { header: 'Title', render: (l) => <>{l.title}<div className="muted small mono">{l.barcode}</div></> },
          { header: 'Borrowed', render: (l) => <>{fmtDate(l.checkoutAt)}{l.checkedOutBy && <div className="muted small">by {l.checkedOutBy}</div>}</> },
          { header: 'Due', render: (l) => <span className="nowrap">{fmtDate(l.dueAt)} {l.overdue && <span className="badge bad">{l.overdueDays}d late</span>}</span> },
          { header: 'Renewed', render: (l) => `${l.renewals}/${l.maxRenewals}` },
          { header: '', render: (l) => <div className="row-actions">
            <button className="btn small" onClick={() => act(() => api(`/api/circulation/loans/${l.id}/renew`, { method: 'POST' }), 'Renewed')}>Renew</button>
            {isLibrarian && <button className="btn small danger" onClick={() => markLost(l)}>Lost</button>}
          </div> },
        ]} />}

        {tab === 'history' && <>
          <Table rows={history?.content} rowKey={(l) => l.id} empty="No borrowing history" columns={[
            { header: 'Title', render: (l) => l.title },
            { header: 'Borrowed', render: (l) => <>{fmtDate(l.checkoutAt)}{l.checkedOutBy && <div className="muted small">by {l.checkedOutBy}</div>}</> },
            { header: 'Due', render: (l) => fmtDate(l.dueAt) },
            { header: 'Returned', render: (l) => l.returnedAt ? fmtDate(l.returnedAt) : <span className="badge info">On loan</span> },
          ]} />
          <Pager page={history} onPage={setHistPage} />
        </>}

        {tab === 'holds' && <div className="stack">
          {/* Same layout as Circulation → Holds: released, cancelled and expired holds leave the active lists. */}
          <div>
            <h3 style={{ marginTop: 0 }}>On the hold shelf ({ready.length})</h3>
            <Table rows={ready} rowKey={(h) => h.id} columns={holdCols} empty="No items waiting for pickup" />
          </div>
          <div>
            <h3>Waiting for a copy ({waiting.length})</h3>
            <Table rows={waiting} rowKey={(h) => h.id} columns={holdCols} empty="No pending holds" />
          </div>
          <div>
            <h3>Booked for later ({booked.length})</h3>
            <Table rows={booked} rowKey={(h) => h.id} columns={holdCols} empty="No advance bookings" />
          </div>
          {pastHolds.length > 0 && <div>
            <h3>Past reservations ({pastHolds.length})</h3>
            <Table rows={pastHolds} rowKey={(h) => h.id} columns={[
              { header: 'Title', render: (h) => h.title },
              { header: 'Status', render: (h) => <StatusBadge status={h.status} /> },
              { header: 'Placed', render: (h) => fmtDateTime(h.placedAt) },
              { header: 'Closed', render: (h) => h.closedAt
                ? <>{h.status === 'FULFILLED' ? 'Released' : h.status === 'CANCELLED' ? 'Cancelled' : 'Expired'} by <strong>{h.closedBy}</strong> · {fmtDateTime(h.closedAt)}</>
                : '—' },
            ]} />
          </div>}
        </div>}

        {tab === 'attendance' && <PatronAttendanceTab patronId={p.id} />}

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
      {pinOpen && <PinModal patron={p} onClose={() => setPinOpen(false)} onSaved={() => { setPinOpen(false); refreshAll() }} />}
      {clearanceOpen && <ClearanceModal patron={p} onClose={() => setClearanceOpen(false)} />}
      {holdOpen && <HoldModal patronId={p.id} onClose={() => setHoldOpen(false)} onSaved={() => { setHoldOpen(false); refreshAll(); setTab('holds') }} />}
      {photoOpen && <PatronPhotoModal patron={p} onClose={() => setPhotoOpen(false)} onSaved={() => { setPhotoOpen(false); refreshAll() }} />}
      {receipt && <ReceiptModal receipt={receipt} onClose={() => setReceipt(null)} />}
      {erasing && <AnonymizeModal patronId={p.id} name={p.fullName} onClose={() => setErasing(false)} onDone={() => { setErasing(false); refreshAll() }} />}
    </>
  )
}

function PinModal({ patron, onClose, onSaved }: { patron: Patron; onClose: () => void; onSaved: () => void }) {
  const lib = useLibrary()
  const [pin, setPin] = useState('')
  const [printIt, setPrintIt] = useState(true)
  const [error, setError] = useState<string | null>(null)
  async function save() {
    try {
      await api(`/api/patrons/${patron.id}/pin`, { body: { pin } })
      if (printIt) void printSlip(<PatronSlip lib={lib} patron={patron} pin={pin} />)
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
        <label className="check"><input type="checkbox" checked={printIt} onChange={(e) => setPrintIt(e.target.checked)} />
          Print a PIN slip for the patron (receipt printer)</label>
      </div>
    </Modal>
  )
}

interface Clearance { reference: string; patronName: string; cardNumber: string; category: string; course: string | null
  purpose: string | null; issuedAt: string; issuedBy: string; stillClear: boolean }

/** Library clearance: only when nothing is on loan and no fines are unpaid; printed with a reference number. */
function ClearanceModal({ patron, onClose }: { patron: Patron; onClose: () => void }) {
  const lib = useLibrary()
  const toast = useToast()
  const { data: check, error } = useAsync(() => api<{ cleared: boolean; reasons: string[] }>(`/api/patrons/${patron.id}/clearance-check`), [patron.id])
  const [purpose, setPurpose] = useState('')
  const [issued, setIssued] = useState<Clearance | null>(null)
  const [busy, setBusy] = useState(false)
  async function issue() {
    setBusy(true)
    try {
      const c = await api<Clearance>(`/api/patrons/${patron.id}/clearance`, { body: { purpose } })
      setIssued(c)
      void printSlip(<ClearanceSlip lib={lib} c={c} />)
    } catch (e) { toast(errorText(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="Library clearance" onClose={onClose} footer={issued
      ? <><button className="btn" onClick={() => void printSlip(<ClearanceSlip lib={lib} c={issued} />)}><Icon name="printer" />Reprint</button>
          <button className="btn primary" onClick={onClose}>Done</button></>
      : <><button className="btn" onClick={onClose}>Cancel</button>
          {check?.cleared && <button className="btn primary" disabled={busy} onClick={issue}><Icon name="printer" />Issue and print</button>}</>}>
      <div className="stack">
        <ErrorAlert error={error} />
        {!check ? <p className="muted">Checking…</p> : issued ? (
          <div className="alert success">Clearance <strong className="mono">{issued.reference}</strong> issued to {issued.patronName}.
            Anyone can verify it on the Patrons page with this number.</div>
        ) : check.cleared ? <>
          <div className="alert success"><strong>{patron.fullName}</strong> has nothing on loan and no unpaid fines.</div>
          <Field label="Purpose (optional)" hint="e.g. Graduation, Transfer, End of contract"><input value={purpose} maxLength={120}
            onChange={(e) => setPurpose(e.target.value)} /></Field>
        </> : (
          <div className="alert error"><strong>Not cleared yet:</strong><ul>{check.reasons.map((r) => <li key={r}>{r}</li>)}</ul></div>
        )}
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
          { header: '', render: (r) => <span className="row-actions">
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

interface PatronAttendance {
  totalVisits: number; visitsThisMonth: number; totalMinutes: number; averageMinutes: number; lastVisit: string | null
  recent: { id: number; timeIn: string; timeOut: string | null; method: string }[]
}

const hm = (min: number) => (min >= 60 ? `${Math.floor(min / 60)}h ${min % 60}m` : `${min}m`)

/** One patron's library visits: totals and the latest 50 visits. */
function PatronAttendanceTab({ patronId }: { patronId: number }) {
  const { data } = useAsync(() => api<PatronAttendance>(`/api/attendance/patrons/${patronId}`), [patronId])
  if (!data) return <p className="muted">Loading…</p>
  return (
    <div className="stack">
      <div className="stats">
        <div className="stat"><div className="label">Total visits</div><div className="value">{data.totalVisits}</div></div>
        <div className="stat"><div className="label">This month</div><div className="value">{data.visitsThisMonth}</div></div>
        <div className="stat"><div className="label">Time in library</div><div className="value">{hm(data.totalMinutes)}</div></div>
        <div className="stat"><div className="label">Average visit</div><div className="value">{hm(data.averageMinutes)}</div></div>
      </div>
      <Table rows={data.recent} rowKey={(v) => v.id} empty="No visits recorded" columns={[
        { header: 'Time in', render: (v) => fmtDateTime(v.timeIn) },
        { header: 'Time out', render: (v) => v.timeOut ? fmtDateTime(v.timeOut) : <span className="badge ok">Inside</span> },
        { header: 'Stayed', render: (v) => v.timeOut ? hm(Math.round((new Date(v.timeOut).getTime() - new Date(v.timeIn).getTime()) / 60000)) : '—' },
        { header: 'Via', render: (v) => <StatusBadge status={v.method} /> },
      ]} />
    </div>
  )
}

/** Right to erasure: removes the patron's identity but keeps anonymous loan and fine statistics. */
function AnonymizeModal({ patronId, name, onClose, onDone }: { patronId: number; name: string; onClose: () => void; onDone: () => void }) {
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit() {
    setBusy(true)
    try {
      await api(`/api/patrons/${patronId}/anonymize`, { body: { reason } })
      toast('Patron anonymized')
      onDone()
    } catch (e) { toast(errorText(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="Anonymize patron" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn danger solid" disabled={busy || confirm.trim().toUpperCase() !== 'ANONYMIZE'} onClick={submit}>Anonymize</button>
    </>}>
      <div className="stack">
        <p style={{ margin: 0 }}>This permanently removes <strong>{name}</strong>'s name, card number, RFID card, email, phone, photo and PIN,
          cancels open reservations and deletes their notices. Their past loans and fines stay as anonymous statistics.
          It can't be undone. Download their data first if they asked for a copy.</p>
        <p className="muted small" style={{ margin: 0 }}>Items still on loan must be checked in and unpaid fines settled or waived first.</p>
        <div className="form-grid">
          <Field label="Reason" className="span-all" hint="Recorded in the audit trail, e.g. “Patron's written request, 2026-11-11”">
            <input value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} /></Field>
          <Field label='Type "ANONYMIZE" to confirm' className="span-all"><input value={confirm} onChange={(e) => setConfirm(e.target.value)} /></Field>
        </div>
      </div>
    </Modal>
  )
}
