import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../../api/client'
import type { CheckinResult, CheckoutResult, Patron, PatronSummary } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { StatusBadge, Table, Tabs } from '../../components/ui'
import { fmtDate, fmtMoney } from '../../format'

type Mode = 'out' | 'in'

interface LogEntry { id: number; ok: boolean; text: string }

/**
 * Circulation desk. Works with USB/wireless barcode scanners and RFID pads that act as a
 * keyboard: focus stays in the scan box and each scan ends with Enter.
 */
export function CirculationPage() {
  const [mode, setMode] = useState<Mode>('out')
  const [log, setLog] = useState<LogEntry[]>([])
  const addLog = (ok: boolean, text: string) => setLog((l) => [{ id: Date.now() + Math.random(), ok, text }, ...l].slice(0, 30))

  return (
    <>
      <div className="page-head"><h1>Circulation desk</h1></div>
      <Tabs value={mode} onChange={setMode} tabs={[{ id: 'out', label: 'Check out / renew' }, { id: 'in', label: 'Check in (return)' }]} />
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        {mode === 'out' ? <Checkout addLog={addLog} /> : <Checkin addLog={addLog} />}
        <div className="card">
          <h2>This session</h2>
          {log.length === 0 ? <p className="muted small">Transactions will appear here.</p> : (
            <div className="stack" style={{ gap: 6 }}>
              {log.map((e) => <div key={e.id} className={`alert ${e.ok ? 'success' : 'error'} small`}>{e.text}</div>)}
            </div>
          )}
        </div>
      </div>
    </>
  )
}

function Checkout({ addLog }: { addLog: (ok: boolean, t: string) => void }) {
  const session = useSession()
  const canOverride = hasRole(session, 'ADMIN', 'LIBRARIAN')
  const [patronCode, setPatronCode] = useState('')
  const [summary, setSummary] = useState<PatronSummary | null>(null)
  const [itemCode, setItemCode] = useState('')
  const [override, setOverride] = useState(false)
  const [busy, setBusy] = useState(false)
  const itemRef = useRef<HTMLInputElement>(null)
  const toast = useToast()

  async function loadPatron(e?: FormEvent) {
    e?.preventDefault()
    if (!patronCode.trim()) return
    try {
      const p = await api<Patron>(`/api/patrons/lookup${qs({ code: patronCode.trim() })}`)
      setSummary(await api<PatronSummary>(`/api/circulation/patrons/${p.id}/summary`))
      setOverride(false)
      setTimeout(() => itemRef.current?.focus(), 0)
    } catch (err) {
      setSummary(null)
      toast(errorText(err), 'error')
    }
  }

  async function refresh() {
    if (summary) setSummary(await api<PatronSummary>(`/api/circulation/patrons/${summary.patron.id}/summary`))
  }

  async function checkout(e: FormEvent) {
    e.preventDefault()
    if (!summary || !itemCode.trim()) return
    setBusy(true)
    try {
      const r = await api<CheckoutResult>('/api/circulation/checkout', {
        body: { patron: summary.patron.cardNumber, item: itemCode.trim(), override },
      })
      addLog(true, `OUT ${r.loan.barcode} "${r.loan.title}" → ${r.loan.patronName}, due ${fmtDate(r.loan.dueAt)}`
        + (r.warnings.length ? ` (override: ${r.warnings.join('; ')})` : '')
        + (r.notice ? ` — SHORT LOAN: ${r.notice}` : ''))
      if (r.notice) toast(`Short loan — ${r.notice}. Tell the patron.`, 'warn')
      setItemCode('')
      await refresh()
    } catch (err) {
      addLog(false, `${itemCode}: ${errorText(err)}`)
      toast(errorText(err), 'error')
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
      <form className="stack" onSubmit={loadPatron}>
        <label className="field">
          <span>1. Scan patron card (barcode / RFID) or type card number</span>
          <div className="input-group">
            <input className="scan-input" value={patronCode} onChange={(e) => setPatronCode(e.target.value)} autoFocus
              placeholder="Patron card" autoComplete="off" />
            <button className="btn">Load</button>
          </div>
        </label>
      </form>

      {summary && (
        <>
          <div className="row">
            <div style={{ flex: 1, minWidth: 0 }}>
              <Link to={`/staff/patrons/${summary.patron.id}`}><strong>{summary.patron.fullName}</strong></Link>
              <div className="muted small">
                {summary.patron.cardNumber} · {summary.patron.categoryName}
                {summary.patron.courseCode && ` · ${summary.patron.courseCode}`} · {summary.loans.length}/{summary.maxLoans} loans
                {summary.unpaidTotal > 0 && <> · <span style={{ color: 'var(--danger)' }}>{fmtMoney(summary.unpaidTotal)} unpaid</span></>}
              </div>
            </div>
            <button className="btn small" onClick={() => { setSummary(null); setPatronCode('') }}>Clear</button>
          </div>
          {summary.blocks.length > 0 && (
            <div className="alert warn small"><strong>Attention:</strong><ul>{summary.blocks.map((b) => <li key={b}>{b}</li>)}</ul></div>
          )}
          <form className="stack" onSubmit={checkout}>
            <label className="field">
              <span>2. Scan item barcode / RFID tag</span>
              <div className="input-group">
                <input ref={itemRef} className="scan-input" value={itemCode} onChange={(e) => setItemCode(e.target.value)}
                  placeholder="Item barcode" autoComplete="off" />
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

function Checkin({ addLog }: { addLog: (ok: boolean, t: string) => void }) {
  const [code, setCode] = useState('')
  const [last, setLast] = useState<CheckinResult | null>(null)
  const [busy, setBusy] = useState(false)
  const ref = useRef<HTMLInputElement>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!code.trim()) return
    setBusy(true)
    try {
      const r = await api<CheckinResult>('/api/circulation/checkin', { body: { item: code.trim() } })
      setLast(r)
      addLog(true, `IN ${r.loan?.barcode ?? code} ${r.loan ? `"${r.loan.title}" from ${r.loan.patronName}` : ''} — ${r.message}`
        + (r.fineAmount > 0 ? ` · fine ${fmtMoney(r.fineAmount)}` : ''))
      setCode('')
    } catch (err) {
      setLast(null)
      addLog(false, `${code}: ${errorText(err)}`)
    } finally {
      setBusy(false)
      ref.current?.focus()
    }
  }

  return (
    <div className="card stack">
      <form onSubmit={submit}>
        <label className="field">
          <span>Scan returned item (barcode / RFID tag)</span>
          <div className="input-group">
            <input ref={ref} className="scan-input" value={code} onChange={(e) => setCode(e.target.value)} autoFocus
              placeholder="Item barcode" autoComplete="off" />
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
        </div>
      )}
    </div>
  )
}
