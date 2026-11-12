import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { api } from '../../api/client'
import { useLibrary } from '../../api/library'
import type { Loan } from '../../api/types'
import { useKioskConnected } from '../../auth/AuthContext'
import { BroadcastOverlay } from '../../components/BroadcastOverlay'
import { Icon } from '../../components/Icon'
import { Logo } from '../../components/Logo'
import { errorText } from '../../components/Toast'
import { fmtDate, fmtMoney } from '../../format'
import { useInactivity } from '../../hooks/useInactivity'
import { KioskResetZone, KioskSetup } from '../../components/KioskSetup'
import { CheckoutSlip, printRecorded, ReturnSlip, type ReturnedItem, type SlipLoan } from '../../print/slips'

interface Config { requirePin: boolean; allowReturns: boolean; idleSeconds: number }
interface PatronView { firstName: string; fullName: string; cardNumber: string; loans: Loan[]; maxLoans: number; unpaidTotal: number; blocks: string[] }
interface Checkout { title: string; barcode: string; dueAt: string; notice: string | null; patron: PatronView }
interface Returned { title: string | null; barcode: string | null; dueAt: string | null; returnedAt: string | null; fine: number; message: string }
interface Line { id: number; ok: boolean; title: string; detail: string }
/** An item scan, with the patron session it was made in. */
interface ScanJob { code: string; step: Step; card: string; pin: string; session: number }

type Step = 'card' | 'pin' | 'borrow' | 'return' | 'done'

/**
 * Self-checkout kiosk at `/kiosk/selfcheck`. Works with a USB barcode scanner or an RFID pad in
 * keyboard mode: the scan box always has focus and each scan ends with Enter.
 */
export function SelfCheckKiosk() {
  const connected = useKioskConnected()
  if (!connected) return <KioskSetup title="Self-checkout kiosk setup" />
  return <SelfCheckScreen />
}

const opts = { slot: 'kiosk' as const }

function SelfCheckScreen() {
  const lib = useLibrary()
  const [config, setConfig] = useState<Config>({ requirePin: true, allowReturns: true, idleSeconds: 90 })
  const [step, setStep] = useState<Step>('card')
  const [card, setCard] = useState('')
  const [pin, setPin] = useState('')
  const [patron, setPatron] = useState<PatronView | null>(null)
  const [lines, setLines] = useState<Line[]>([])
  const [slipLoans, setSlipLoans] = useState<SlipLoan[]>([])
  /** Items returned this session, for the return receipt (no patron details: anyone can return a book). */
  const [slipReturns, setSlipReturns] = useState<ReturnedItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const scanRef = useRef<HTMLInputElement>(null)
  const [scan, setScan] = useState('')
  /** Bumped by every reset: responses from an earlier patron's session are dropped. */
  const session = useRef(0)
  /** Item scans waiting for the server, sent one at a time so back-to-back RFID reads are not lost. */
  const pending = useRef<ScanJob[]>([])
  const running = useRef(false)
  // The PIN is kept only in memory for this patron's session and sent with each request.
  const savedPin = useRef('')

  useEffect(() => { void api<Config>('/api/kiosk/selfcheck/config', opts).then(setConfig).catch(() => {}) }, [])

  const reset = useCallback(() => {
    session.current++; pending.current = []; savedPin.current = ''
    setStep('card'); setCard(''); setPin(''); setPatron(null); setLines([]); setSlipLoans([]); setSlipReturns([]); setError(null); setScan('')
  }, [])
  useInactivity((step === 'done' ? 20 : config.idleSeconds) * 1000, reset)

  // Keep the scan box focused so scanners and RFID pads always type into it.
  useEffect(() => {
    if (step === 'pin') return
    const focus = () => scanRef.current?.focus()
    focus()
    const t = window.setInterval(focus, 1000)
    return () => window.clearInterval(t)
  }, [step])

  const add = (ok: boolean, title: string, detail: string) =>
    setLines((l) => [{ id: Date.now() + Math.random(), ok, title, detail }, ...l])

  async function identify(code: string, withPin: string) {
    const s = session.current
    setBusy(true); setError(null)
    try {
      const p = await api<PatronView>('/api/kiosk/selfcheck/identify', { ...opts, body: { card: code, pin: withPin || null } })
      if (s !== session.current) return
      setPatron(p); setStep('borrow'); setPin('')
    } catch (e) {
      if (s !== session.current) return
      setError(errorText(e)); setPin('')
      if (!config.requirePin || !/PIN/i.test(errorText(e))) { setStep('card'); setCard('') }
    } finally { setBusy(false) }
  }

  function onScan(e: FormEvent) {
    e.preventDefault()
    const code = scan.trim()
    setScan('')
    if (!code) return
    if (step === 'card') {
      if (busy) return // still checking the card just scanned
      setCard(code)
      if (config.requirePin) { setStep('pin'); setError(null) } else void identify(code, '')
      return
    }
    if (step !== 'borrow' && step !== 'return') return
    setError(null)
    pending.current.push({ code, step, card, pin: savedPin.current, session: session.current })
    void drain()
  }

  async function drain() {
    if (running.current) return
    running.current = true
    try {
      for (let job = pending.current.shift(); job; job = pending.current.shift()) {
        if (job.session === session.current) await scanOne(job)
      }
    } finally {
      running.current = false
    }
  }

  async function scanOne(job: ScanJob) {
    try {
      if (job.step === 'borrow') {
        const r = await api<Checkout>('/api/kiosk/selfcheck/checkout', { ...opts, body: { patron: { card: job.card, pin: job.pin }, item: job.code } })
        if (job.session !== session.current) return // the kiosk was reset meanwhile
        setPatron(r.patron)
        setSlipLoans((s) => [...s, { title: r.title, barcode: r.barcode, dueAt: r.dueAt }])
        add(true, r.title, `Due ${fmtDate(r.dueAt)}${r.notice ? ` — ${r.notice}` : ''}`)
      } else {
        const r = await api<Returned>('/api/kiosk/selfcheck/return', { ...opts, body: { item: job.code } })
        if (job.session !== session.current) return
        if (r.title && r.barcode) {
          const back: ReturnedItem = { title: r.title, barcode: r.barcode, returnedAt: r.returnedAt ?? new Date().toISOString(), fine: r.fine }
          setSlipReturns((s) => [...s, back])
        }
        add(true, r.title ?? job.code, r.message + (r.fine > 0 ? ` Overdue fine ${fmtMoney(r.fine)}; please pay at the desk.` : ''))
      }
    } catch (err) {
      if (job.session === session.current) add(false, job.code, errorText(err))
    }
  }

  function submitPin() {
    savedPin.current = pin
    void identify(card, pin)
  }

  async function renew(l: Loan) {
    const s = session.current
    try {
      const r = await api<{ pending: boolean; message: string; patron: PatronView }>('/api/kiosk/selfcheck/renew',
        { ...opts, body: { patron: { card, pin: savedPin.current }, loanId: l.id } })
      if (s !== session.current) return
      setPatron(r.patron)
      add(true, l.title, r.message)
    } catch (e) { if (s === session.current) add(false, l.title, errorText(e)) }
  }

  const borrowed = lines.filter((l) => l.ok)

  return (
    <div className="selfcheck">
      <BroadcastOverlay />
      <KioskResetZone />
      <header>
        <Logo size={56} />
        <div><div className="name">{lib.name}</div><div className="sub">Self-checkout</div></div>
        {step !== 'card' && <button className="btn ghost" onClick={reset}><Icon name="x" />Start over</button>}
      </header>

      <form onSubmit={onScan} className={step === 'card' || step === 'borrow' || step === 'return' ? 'scan-form' : 'scan-form hidden'}>
        <input ref={scanRef} value={scan} onChange={(e) => setScan(e.target.value)} autoComplete="off" inputMode="none"
          aria-label={step === 'card' ? 'Library card' : 'Item barcode or RFID tag'}
          placeholder={step === 'card' ? 'Tap or scan your library card' : 'Place or scan the item'} />
      </form>

      <main>
        {error && <div className="alert error big">{error}</div>}

        {step === 'card' && (
          <div className="center stack">
            <div className="big-icon"><Icon name="credit-card" size="1em" /></div>
            <h1>Tap or scan your library card</h1>
            <p className="muted">to borrow or renew books</p>
            {config.allowReturns && <button className="btn big" onClick={() => { setStep('return'); setError(null) }}>
              <Icon name="corner-down-left" />Return items</button>}
          </div>
        )}

        {step === 'pin' && (
          <div className="center stack">
            <h1>Enter your PIN</h1>
            <div className="pin-dots" aria-label={`${pin.length} digits entered`}>{'●'.repeat(pin.length) || ' '}</div>
            <div className="keypad">
              {['1', '2', '3', '4', '5', '6', '7', '8', '9', 'del', '0', 'ok'].map((k) => (
                <button key={k} className={`btn ${k === 'ok' ? 'primary' : ''}`} disabled={busy || (k === 'ok' && pin.length < 4)}
                  onClick={() => k === 'del' ? setPin((p) => p.slice(0, -1)) : k === 'ok' ? submitPin() : setPin((p) => (p.length < 8 ? p + k : p))}>
                  {k === 'del' ? <Icon name="delete" /> : k === 'ok' ? 'OK' : k}
                </button>
              ))}
            </div>
            <p className="muted">Forgot your PIN? Use “Forgot PIN” on the My Library page, or ask at the desk.</p>
          </div>
        )}

        {(step === 'borrow' || step === 'return') && (
          <div className="grid cols-2" style={{ alignItems: 'start' }}>
            <div className="card stack">
              {step === 'borrow' && patron ? <>
                <h1 style={{ margin: 0 }}>Hi, {patron.firstName}!</h1>
                <p className="lead">Place each item on the pad or scan its barcode.</p>
                {patron.blocks.length > 0 && <div className="alert warn">{patron.blocks.join(' · ')}. Some items may need the desk.</div>}
                <div className="muted">{patron.loans.length} of {patron.maxLoans} items on loan
                  {patron.unpaidTotal > 0 && <> · <strong>{fmtMoney(patron.unpaidTotal)}</strong> unpaid</>}</div>
              </> : <>
                <h1 style={{ margin: 0 }}>Return items</h1>
                <p className="lead">Scan each item, then place it on the return cart.</p>
              </>}
              <div className="lines">
                {lines.length === 0 ? <p className="muted">Nothing scanned yet.</p> : lines.map((l) => (
                  <div key={l.id} className={`alert ${l.ok ? 'success' : 'error'}`}>
                    <strong>{l.title}</strong><div>{l.detail}</div>
                  </div>
                ))}
              </div>
              <button className="btn primary big" onClick={() => setStep('done')}><Icon name="check" />Finish</button>
            </div>
            {step === 'borrow' && patron && (
              <div className="card stack">
                <h2 style={{ margin: 0 }}>Your loans</h2>
                {patron.loans.length === 0 ? <p className="muted">No items on loan.</p> : patron.loans.map((l) => (
                  <div key={l.id} className="row loan-row">
                    <div style={{ flex: 1, minWidth: 0 }}><strong>{l.title}</strong>
                      <div className={l.overdue ? 'overdue' : 'muted'}>Due {fmtDate(l.dueAt)}{l.overdue && ' — overdue'}</div></div>
                    {!l.overdue && l.renewals < l.maxRenewals && <button className="btn" onClick={() => renew(l)}>Renew</button>}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {step === 'done' && (
          <div className="center stack">
            <div className="big-icon ok"><Icon name="check-circle" size="1em" /></div>
            <h1>{patron ? `Thank you, ${patron.firstName}!` : 'Thank you!'}</h1>
            {borrowed.length > 0 && <div className="card stack" style={{ textAlign: 'left', minWidth: 'min(100%, 480px)' }}>
              <strong>{lib.name}</strong>
              <span className="muted">{new Date().toLocaleString('en-PH')}{patron && ` · ${patron.fullName} (${patron.cardNumber})`}</span>
              {borrowed.map((l) => <div key={l.id}><strong>{l.title}</strong><br /><span>{l.detail}</span></div>)}
            </div>}
            <div className="row" style={{ justifyContent: 'center' }}>
              {slipLoans.length > 0 && patron && <button className="btn big" onClick={() => void printRecorded(
                { kind: 'CHECKOUT_SLIP', cardNumber: patron.cardNumber, details: `Self-checkout: ${slipLoans.map((l) => l.barcode).join(', ')}` },
                <CheckoutSlip lib={lib} patron={patron} borrowed={slipLoans}
                  otherLoans={patron.loans.filter((l) => !slipLoans.some((s) => s.barcode === l.barcode))} />, true)}>
                <Icon name="printer" />Print slip</button>}
              {slipReturns.length > 0 && <button className="btn big" onClick={() => void printRecorded(
                { kind: 'RETURN_SLIP', details: `Self-return: ${slipReturns.map((i) => i.barcode).join(', ')}` },
                <ReturnSlip lib={lib} items={slipReturns} />, true)}>
                <Icon name="printer" />Print return receipt</button>}
              <button className="btn primary big" onClick={reset}>Done</button>
            </div>
          </div>
        )}
      </main>
    </div>
  )
}
