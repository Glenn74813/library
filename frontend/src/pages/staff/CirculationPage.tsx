import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import { clearFailed, enqueue, failedSyncs, isOffline, offlineQueue, syncOffline } from '../../api/offlineCirculation'
import type { CheckinResult, CheckoutResult, Loan, Patron, PatronSummary } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { CameraScanButton } from '../../components/CameraScan'
import { StatusBadge, Table, Tabs } from '../../components/ui'
import { Icon } from '../../components/Icon'
import { fmtDate, fmtDateTime, fmtMoney } from '../../format'
import { useLibrary } from '../../api/library'
import { CheckoutSlip, getPrintPrefs, HoldShelfSlip, printSlip, ReturnSlip, setPrintPrefs, type PrintPrefs, type ReturnedItem } from '../../print/slips'

type Mode = 'out' | 'in'

/** A desk request that takes longer than this switches to offline mode instead of keeping the patron waiting. */
const DESK_TIMEOUT = 8000

interface LogEntry { id: number; ok: boolean; text: string }

/** Offline state shared by the desk: how many transactions wait in this browser, and a way to queue one. */
interface Offline { queued: number; offline: boolean; queue: (tx: Parameters<typeof enqueue>[0]) => void; markOnline: () => void }

/**
 * Circulation desk. Works with USB/wireless barcode scanners and RFID pads that act as a
 * keyboard: focus stays in the scan box and each scan ends with Enter. When the server can't be
 * reached, checkouts and returns are saved in this browser and uploaded once it is back.
 */
export function CirculationPage() {
  const toast = useToast()
  const [mode, setMode] = useState<Mode>('out')
  const [log, setLog] = useState<LogEntry[]>([])
  const [queued, setQueued] = useState(offlineQueue().length)
  const [offline, setOffline] = useState(false)
  const [failed, setFailed] = useState(failedSyncs())
  const addLog = useCallback((ok: boolean, text: string) =>
    setLog((l) => [{ id: Date.now() + Math.random(), ok, text }, ...l].slice(0, 30)), [])

  const sync = useCallback(async (manual = false) => {
    if (offlineQueue().length === 0) { setQueued(0); return }
    try {
      const results = await syncOffline()
      setOffline(false)
      setQueued(offlineQueue().length)
      setFailed(failedSyncs())
      results.forEach((r) => addLog(r.ok, `SYNCED ${r.type === 'CHECKOUT' ? 'OUT' : 'IN'} ${r.item}: ${r.message}`
        + (r.warnings.length ? ` (rules overridden: ${r.warnings.join('; ')})` : '')))
      const bad = results.filter((r) => !r.ok).length
      toast(`Uploaded ${results.length - bad} offline transaction(s)` + (bad ? `; ${bad} need attention` : ''), bad ? 'warn' : 'success')
    } catch (e) {
      if (isOffline(e)) { setOffline(true); if (manual) toast('Still offline. Transactions stay saved on this computer.', 'warn') }
      else toast(errorText(e), 'error')
    }
  }, [addLog, toast])

  useEffect(() => {
    void sync()
    const t = setInterval(() => void sync(), 30_000)
    const onOnline = () => void sync()
    window.addEventListener('online', onOnline)
    return () => { clearInterval(t); window.removeEventListener('online', onOnline) }
  }, [sync])

  const off: Offline = {
    queued, offline,
    queue: (tx) => { setQueued(enqueue(tx)); setOffline(true) },
    markOnline: () => setOffline(false),
  }

  return (
    <>
      <div className="page-head"><h1>Circulation desk</h1></div>
      {(offline || queued > 0) && (
        <div className="alert warn row" style={{ marginBottom: 16, justifyContent: 'space-between' }}>
          <span><strong>{offline ? 'Offline mode.' : 'Waiting to upload.'}</strong> {queued} transaction(s) saved on this computer;
            they upload automatically when the server is reachable. Keep this browser open and don't clear its data.</span>
          <button className="btn small" onClick={() => void sync(true)}>Upload now</button>
        </div>
      )}
      {failed.length > 0 && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2 style={{ margin: 0 }}>Offline transactions that need attention</h2>
            <button className="btn small" onClick={() => { clearFailed(); setFailed([]) }}>Dismiss all</button>
          </div>
          <p className="muted small">These could not be applied. Fix them by hand (e.g. check the item in or out again), then dismiss.</p>
          <Table rows={failed} rowKey={(f) => f.id} columns={[
            { header: 'When', render: (f) => (f.at ? fmtDateTime(f.at) : '—') },
            { header: 'Action', render: (f) => (f.type === 'CHECKOUT' ? 'Check out' : 'Check in') },
            { header: 'Item', render: (f) => <span className="mono">{f.item}</span> },
            { header: 'Patron', render: (f) => <span className="mono">{f.patron ?? ''}</span> },
            { header: 'Problem', render: (f) => f.message },
          ]} />
        </div>
      )}
      <Tabs value={mode} onChange={setMode} tabs={[{ id: 'out', label: 'Check out / renew' }, { id: 'in', label: 'Check in (return)' }]} />
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        {mode === 'out' ? <Checkout addLog={addLog} off={off} /> : <Checkin addLog={addLog} off={off} />}
        <div className="stack">
        <PrintSettings />
        <div className="card">
          <h2>This session</h2>
          {log.length === 0 ? <p className="muted small">Transactions will appear here.</p> : (
            <div className="stack" style={{ gap: 6 }}>
              {log.map((e) => <div key={e.id} className={`alert ${e.ok ? 'success' : 'error'} small`}>{e.text}</div>)}
            </div>
          )}
        </div>
        </div>
      </div>
    </>
  )
}

function Checkout({ addLog, off }: { addLog: (ok: boolean, t: string) => void; off: Offline }) {
  const session = useSession()
  const canOverride = hasRole(session, 'ADMIN', 'LIBRARIAN')
  const [patronCode, setPatronCode] = useState('')
  const [summary, setSummary] = useState<PatronSummary | null>(null)
  /** Card number accepted without a server lookup while offline. */
  const [offlinePatron, setOfflinePatron] = useState<string | null>(null)
  const [itemCode, setItemCode] = useState('')
  const [override, setOverride] = useState(false)
  const [busy, setBusy] = useState(false)
  /** Items this patron borrowed during the current visit, for the checkout slip. */
  const [visit, setVisit] = useState<Loan[]>([])
  const itemRef = useRef<HTMLInputElement>(null)
  const patronForm = useRef<HTMLFormElement>(null)
  const itemForm = useRef<HTMLFormElement>(null)
  const toast = useToast()
  const lib = useLibrary()

  function printCheckoutSlip(s: PatronSummary, borrowed: Loan[]) {
    void printSlip(<CheckoutSlip lib={lib} patron={s.patron} borrowed={borrowed}
      otherLoans={s.loans.filter((l) => !borrowed.some((b) => b.id === l.id))} />)
  }

  /** Done with this patron: print their slip if this desk does that automatically, then clear. */
  function finishPatron() {
    if (summary && visit.length > 0 && getPrintPrefs().checkoutOnFinish) printCheckoutSlip(summary, visit)
    setSummary(null)
    setPatronCode('')
    setVisit([])
  }

  async function loadPatron(e?: FormEvent) {
    if (summary && visit.length > 0 && summary.patron.cardNumber !== patronCode.trim()) {
      if (getPrintPrefs().checkoutOnFinish) printCheckoutSlip(summary, visit)
      setVisit([])
    }
    e?.preventDefault()
    if (!patronCode.trim()) return
    try {
      const p = await api<Patron>(`/api/patrons/lookup${qs({ code: patronCode.trim() })}`, { timeoutMs: DESK_TIMEOUT })
      setSummary(await api<PatronSummary>(`/api/circulation/patrons/${p.id}/summary`, { timeoutMs: DESK_TIMEOUT }))
      setOfflinePatron(null)
      off.markOnline()
      setOverride(false)
      setTimeout(() => itemRef.current?.focus(), 0)
    } catch (err) {
      setSummary(null)
      if (isOffline(err)) {
        setOfflinePatron(patronCode.trim())
        setTimeout(() => itemRef.current?.focus(), 0)
      } else {
        setOfflinePatron(null)
        toast(errorText(err), 'error')
      }
    }
  }

  async function refresh() {
    if (summary) setSummary(await api<PatronSummary>(`/api/circulation/patrons/${summary.patron.id}/summary`).catch(() => summary))
  }

  function queueCheckout(patron: string) {
    off.queue({ type: 'CHECKOUT', patron, item: itemCode.trim() })
    addLog(true, `OFFLINE OUT ${itemCode.trim()} → card ${patron} (saved; uploads when back online)`)
    setItemCode('')
  }

  async function checkout(e: FormEvent) {
    e.preventDefault()
    if (!itemCode.trim()) return
    if (!summary) {
      if (offlinePatron) queueCheckout(offlinePatron)
      itemRef.current?.focus()
      return
    }
    setBusy(true)
    try {
      const r = await api<CheckoutResult>('/api/circulation/checkout', {
        body: { patron: summary.patron.cardNumber, item: itemCode.trim(), override }, timeoutMs: DESK_TIMEOUT,
      })
      setVisit((v) => [...v, r.loan])
      addLog(true, `OUT ${r.loan.barcode} "${r.loan.title}" → ${r.loan.patronName}, due ${fmtDate(r.loan.dueAt)}`
        + (r.warnings.length ? ` (override: ${r.warnings.join('; ')})` : '')
        + (r.notice ? ` — SHORT LOAN: ${r.notice}` : ''))
      if (r.notice) toast(`Short loan — ${r.notice}. Tell the patron.`, 'warn')
      setItemCode('')
      await refresh()
    } catch (err) {
      if (isOffline(err)) {
        queueCheckout(summary.patron.cardNumber)
      } else {
        addLog(false, `${itemCode}: ${errorText(err)}`)
        toast(errorText(err), 'error')
      }
    } finally {
      setBusy(false)
      itemRef.current?.focus()
    }
  }

  async function renew(loanId: number) {
    try {
      const l = await api<{ title: string; dueAt: string }>(`/api/circulation/loans/${loanId}/renew`, { method: 'POST' })
      addLog(true, `Renewed "${l.title}", now due ${fmtDate(l.dueAt)}`)
      await refresh()
    } catch (err) {
      toast(errorText(err), 'error')
    }
  }

  return (
    <div className="card stack">
      <form className="stack" onSubmit={loadPatron} ref={patronForm}>
        <label className="field">
          <span>1. Scan patron card (barcode / RFID) or type card number</span>
          <div className="input-group">
            <input className="scan-input" value={patronCode} onChange={(e) => setPatronCode(e.target.value)} autoFocus
              placeholder="Patron card" autoComplete="off" />
            <CameraScanButton onScan={(t) => { setPatronCode(t); setTimeout(() => patronForm.current?.requestSubmit(), 0) }} />
            <button className="btn">Load</button>
          </div>
        </label>
      </form>

      {offlinePatron && !summary && (
        <>
          <div className="alert warn small">
            <strong>Offline:</strong> card <span className="mono">{offlinePatron}</span> can't be verified right now. Loans are saved on this
            computer and checked against the loan rules when they upload; any rule they break is flagged for review.
          </div>
          <form className="stack" onSubmit={checkout} ref={itemForm}>
            <label className="field">
              <span>2. Scan item barcode / RFID tag</span>
              <div className="input-group">
                <input ref={itemRef} className="scan-input" value={itemCode} onChange={(e) => setItemCode(e.target.value)}
                  placeholder="Item barcode" autoComplete="off" />
                <CameraScanButton onScan={(t) => { setItemCode(t); setTimeout(() => itemForm.current?.requestSubmit(), 0) }} />
                <button className="btn primary">Save checkout</button>
              </div>
            </label>
          </form>
          <div className="row end"><button className="btn small" onClick={() => { setOfflinePatron(null); setPatronCode('') }}>Next patron</button></div>
        </>
      )}
      {summary && (
        <>
          <div className="row">
            {summary.patron.photoUrl && <img src={summary.patron.photoUrl} alt="" className="avatar" style={{ cursor: 'default' }} />}
            <div style={{ flex: 1, minWidth: 0 }}>
              <Link to={`/staff/patrons/${summary.patron.id}`}><strong>{summary.patron.fullName}</strong></Link>
              <div className="muted small">
                {summary.patron.cardNumber} · {summary.patron.categoryName}
                {summary.patron.courseCode && ` · ${summary.patron.courseCode}`} · {summary.loans.length}/{summary.maxLoans} loans
                {summary.unpaidTotal > 0 && <> · <span style={{ color: 'var(--danger)' }}>{fmtMoney(summary.unpaidTotal)} unpaid</span></>}
              </div>
            </div>
            <div className="row-actions">
              <button className="btn small" title="Print the borrowing slip for this visit"
                onClick={() => printCheckoutSlip(summary, visit)}><Icon name="printer" />Slip{visit.length > 0 && ` (${visit.length})`}</button>
              <button className="btn small" onClick={finishPatron}>Next patron</button>
            </div>
          </div>
          {summary.blocks.length > 0 && (
            <div className="alert warn small"><strong>Attention:</strong><ul>{summary.blocks.map((b) => <li key={b}>{b}</li>)}</ul></div>
          )}
          <form className="stack" onSubmit={checkout} ref={itemForm}>
            <label className="field">
              <span>2. Scan item barcode / RFID tag</span>
              <div className="input-group">
                <input ref={itemRef} className="scan-input" value={itemCode} onChange={(e) => setItemCode(e.target.value)}
                  placeholder="Item barcode" autoComplete="off" />
                <CameraScanButton onScan={(t) => { setItemCode(t); setTimeout(() => itemForm.current?.requestSubmit(), 0) }} />
                <button className="btn primary" disabled={busy}>Check out</button>
              </div>
            </label>
            {canOverride && (
              <label className="check small"><input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} />
                Override loan limits (logged in audit trail)</label>
            )}
          </form>
          <h3>Current loans</h3>
          <Table rows={summary.loans} rowKey={(l) => l.id} empty="No items on loan" columns={[
            { header: 'Title', render: (l) => <>{l.title}<div className="muted small mono">{l.barcode}</div></> },
            { header: 'Due', render: (l) => <span className="nowrap">{fmtDate(l.dueAt)} {l.overdue && <span className="badge bad">{l.overdueDays}d late</span>}</span> },
            { header: 'Renewed', render: (l) => `${l.renewals}/${l.maxRenewals}` },
            { header: '', render: (l) => <button className="btn small" onClick={() => renew(l.id)}>Renew</button> },
          ]} />
          {summary.holds.filter((h) => h.status === 'READY').length > 0 && (
            <div className="alert">
              <strong>Holds ready for pickup:</strong>
              <ul>{summary.holds.filter((h) => h.status === 'READY').map((h) =>
                <li key={h.id}>{h.title} <span className="mono small">({h.itemBarcode})</span></li>)}</ul>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function Checkin({ addLog, off }: { addLog: (ok: boolean, t: string) => void; off: Offline }) {
  const lib = useLibrary()
  const [code, setCode] = useState('')
  const [last, setLast] = useState<CheckinResult | null>(null)
  /** Returns not printed yet, for one return slip covering several items. */
  const [returns, setReturns] = useState<ReturnedItem[]>([])
  const [busy, setBusy] = useState(false)
  const ref = useRef<HTMLInputElement>(null)
  const form = useRef<HTMLFormElement>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!code.trim()) return
    setBusy(true)
    try {
      const r = await api<CheckinResult>('/api/circulation/checkin', { body: { item: code.trim() }, timeoutMs: DESK_TIMEOUT })
      setLast(r)
      const prefs = getPrintPrefs()
      if (r.loan) {
        const item: ReturnedItem = { title: r.loan.title, barcode: r.loan.barcode, patronName: r.loan.patronName,
          cardNumber: r.loan.cardNumber, returnedAt: r.loan.returnedAt ?? new Date().toISOString(), fine: r.fineAmount }
        if (prefs.returnEach) void printSlip(<ReturnSlip lib={lib} items={[item]} />)
        else setReturns((list) => [...list, item])
      }
      if (r.holdSlip && prefs.holdSlip) void printSlip(<HoldShelfSlip lib={lib} hold={r.holdSlip} />)
      addLog(true, `IN ${r.loan?.barcode ?? code} ${r.loan ? `"${r.loan.title}" from ${r.loan.patronName}` : ''} — ${r.message}`
        + (r.fineAmount > 0 ? ` · fine ${fmtMoney(r.fineAmount)}` : ''))
      setCode('')
      off.markOnline()
    } catch (err) {
      setLast(null)
      if (isOffline(err)) {
        off.queue({ type: 'CHECKIN', item: code.trim() })
        addLog(true, `OFFLINE IN ${code.trim()} (saved; fines and hold routing are worked out when it uploads)`)
        setCode('')
      } else {
        addLog(false, `${code}: ${errorText(err)}`)
      }
    } finally {
      setBusy(false)
      ref.current?.focus()
    }
  }

  return (
    <div className="card stack">
      <form onSubmit={submit} ref={form}>
        <label className="field">
          <span>Scan returned item (barcode / RFID tag)</span>
          <div className="input-group">
            <input ref={ref} className="scan-input" value={code} onChange={(e) => setCode(e.target.value)} autoFocus
              placeholder="Item barcode" autoComplete="off" />
            <CameraScanButton onScan={(t) => { setCode(t); setTimeout(() => form.current?.requestSubmit(), 0) }} />
            <button className="btn primary" disabled={busy}>Check in</button>
          </div>
        </label>
      </form>
      {last && (
        <div className={`alert ${last.holdForPatron ? 'warn' : 'success'}`}>
          <strong style={{ fontSize: '1.1rem' }}>{last.message}</strong>
          {last.loan && <div className="small">{last.loan.title} · returned by {last.loan.patronName}
            {last.loan.overdueDays > 0 || last.fineAmount > 0 ? '' : ' · on time'}</div>}
          {last.fineAmount > 0 && <div><StatusBadge status="UNPAID" /> Overdue fine {fmtMoney(last.fineAmount)} added</div>}
          {last.holdSlip && <div className="row" style={{ marginTop: 6 }}>
            <button className="btn small" onClick={() => void printSlip(<HoldShelfSlip lib={lib} hold={last.holdSlip!} />)}>
              <Icon name="printer" />{getPrintPrefs().holdSlip ? 'Reprint hold slip' : 'Print hold slip'}</button>
          </div>}
        </div>
      )}
      {returns.length > 0 && (
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <span className="muted small">{returns.length} return(s) not printed yet</span>
          <span className="row-actions">
            <button className="btn small" onClick={() => { void printSlip(<ReturnSlip lib={lib} items={returns} />); setReturns([]) }}>
              <Icon name="printer" />Print return slip</button>
            <button className="btn small" onClick={() => setReturns([])}>Clear</button>
          </span>
        </div>
      )}
    </div>
  )
}

/** Which slips this desk's receipt printer prints by itself; saved on this computer. */
function PrintSettings() {
  const [prefs, setPrefs] = useState<PrintPrefs>(getPrintPrefs)
  const set = (patch: Partial<PrintPrefs>) => { const next = { ...prefs, ...patch }; setPrefs(next); setPrintPrefs(next) }
  return (
    <details className="card">
      <summary style={{ cursor: 'pointer' }}><strong><Icon name="printer" /> Receipt printer on this computer</strong></summary>
      <div className="stack" style={{ gap: 6, marginTop: 8 }}>
        <label className="check"><input type="checkbox" checked={prefs.holdSlip} onChange={(e) => set({ holdSlip: e.target.checked })} />
          Print a hold shelf slip when a returned copy is set aside for a reservation</label>
        <label className="check"><input type="checkbox" checked={prefs.checkoutOnFinish} onChange={(e) => set({ checkoutOnFinish: e.target.checked })} />
          Print the borrowing slip when I press Next patron</label>
        <label className="check"><input type="checkbox" checked={prefs.returnEach} onChange={(e) => set({ returnEach: e.target.checked })} />
          Print a return slip for every returned item</label>
        <p className="muted small" style={{ margin: 0 }}>Slips are 80 mm wide. To print without the print dialog, start this browser with
          <span className="mono"> --kiosk-printing</span> and make the receipt printer the default printer.</p>
      </div>
    </details>
  )
}
