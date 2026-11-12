import type { ReactElement, ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import qrcode from 'qrcode-generator'
import type { LibraryProfile } from '../api/library'
import type { Receipt } from '../api/types'
import { fmtDate, fmtDateTime, fmtMoney } from '../format'

/*
 * Slips for the 80 mm thermal receipt printer. Each slip is rendered to plain HTML and printed from a
 * hidden frame sized to the roll, so only the slip prints (never the page behind it). Start the desk
 * browser with --kiosk-printing to print without the print dialog.
 */

const SLIP_CSS = `
  @page { margin: 2mm 3mm; }
  * { box-sizing: border-box; }
  body { margin: 0; width: 74mm; font-family: Arial, Helvetica, sans-serif; font-size: 11px; color: #000; line-height: 1.35; }
  .c { text-align: center; }
  .lib { font-weight: 700; font-size: 13px; }
  .small { font-size: 10px; }
  .title { font-weight: 700; font-size: 14px; text-transform: uppercase; letter-spacing: 0.04em; margin: 4px 0; }
  .big { font-size: 18px; font-weight: 700; }
  .mono { font-family: "Courier New", monospace; }
  hr { border: 0; border-top: 1px dashed #000; margin: 6px 0; }
  table { width: 100%; border-collapse: collapse; }
  td { padding: 2px 0; vertical-align: top; }
  td.r { text-align: right; white-space: nowrap; padding-left: 6px; }
  .item { margin: 4px 0; }
  .item b { display: block; }
  .qr { display: block; margin: 6px auto 2px; width: 30mm; height: 30mm; image-rendering: pixelated; }
  .box { border: 1px solid #000; padding: 4px 6px; margin: 6px 0; }
`

/** Prints one slip on the receipt printer. Resolves once the print dialog (or direct print) is done. */
export function printSlip(slip: ReactElement): Promise<void> {
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>Slip</title><style>${SLIP_CSS}</style></head>`
    + `<body>${renderToStaticMarkup(slip)}</body></html>`
  return new Promise((resolve) => {
    const frame = document.createElement('iframe')
    frame.setAttribute('aria-hidden', 'true')
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden'
    document.body.appendChild(frame)
    const done = () => { setTimeout(() => frame.remove(), 500); resolve() }
    frame.onload = () => {
      const win = frame.contentWindow
      if (!win) return done()
      fitPageToSlip(frame.contentDocument)
      win.addEventListener('afterprint', done, { once: true })
      // Let the QR image decode before printing.
      setTimeout(() => { win.focus(); win.print(); setTimeout(done, 60_000) }, 150)
    }
    frame.srcdoc = html
  })
}

/**
 * Sets the page to the roll's width and exactly the slip's length, so the printer cuts right after it
 * (CSS has no "auto" page height, and without this browsers fall back to A4 or Letter).
 */
export function fitPageToSlip(doc: Document | null) {
  if (!doc?.body) return
  const heightMm = Math.ceil((doc.body.scrollHeight * 25.4) / 96) + 6 // 2 mm margins top and bottom, plus a little slack
  const style = doc.createElement('style')
  style.textContent = `@page { size: 80mm ${heightMm}mm; margin: 2mm 3mm; }`
  doc.head.appendChild(style)
}

// ------------------------------------------------------------------ per-desk preferences

/** Which slips this desk prints by itself (kept per computer, since each desk has its own printer). */
export interface PrintPrefs {
  /** Print the checkout slip when staff finish with a patron (Next patron). */
  checkoutOnFinish: boolean
  /** Print a return slip for every item checked in. */
  returnEach: boolean
  /** Print a hold shelf slip as soon as a returned copy is set aside for a reservation. */
  holdSlip: boolean
}

const PREFS_KEY = 'smartlib.print'
const DEFAULT_PREFS: PrintPrefs = { checkoutOnFinish: false, returnEach: false, holdSlip: true }

export function getPrintPrefs(): PrintPrefs {
  try { return { ...DEFAULT_PREFS, ...JSON.parse(localStorage.getItem(PREFS_KEY) ?? '{}') } } catch { return DEFAULT_PREFS }
}

export function setPrintPrefs(prefs: PrintPrefs) {
  try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)) } catch { /* not saved on this computer */ }
}

// ------------------------------------------------------------------ building blocks

function qrDataUrl(value: string) {
  const qr = qrcode(0, 'M')
  qr.addData(value)
  qr.make()
  return qr.createDataURL(4, 8)
}

function Qr({ value }: { value: string }) {
  return <img className="qr" src={qrDataUrl(value)} alt="" />
}

/** Library heading used on every slip. */
function Slip({ lib, title, children, footer }: { lib: LibraryProfile; title: string; children: ReactNode; footer?: ReactNode }) {
  return (
    <div>
      <div className="c">
        <div className="lib">{lib.name}</div>
        {lib.institution && <div className="small">{lib.institution}</div>}
        {lib.address && <div className="small">{lib.address}</div>}
        {(lib.phone || lib.email) && <div className="small">{[lib.phone, lib.email].filter(Boolean).join(' · ')}</div>}
        <div className="title">{title}</div>
      </div>
      <hr />
      {children}
      <hr />
      {footer}
      <div className="c small">Printed {fmtDateTime(new Date().toISOString())}</div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <table><tbody><tr><td>{label}</td><td className="r">{value}</td></tr></tbody></table>
}

const myLibraryUrl = () => `${window.location.origin}/my`

// ------------------------------------------------------------------ slips

export interface SlipPatron { fullName: string; cardNumber: string }

/** A loan line on a slip (from a desk checkout, the patron's loan list or the self-checkout kiosk). */
export interface SlipLoan { id?: number; title: string; barcode: string; dueAt: string }

/** Checkout slip: what was borrowed now, with due dates, plus everything else still on loan. */
export function CheckoutSlip({ lib, patron, borrowed, otherLoans }: {
  lib: LibraryProfile; patron: SlipPatron; borrowed: SlipLoan[]; otherLoans?: SlipLoan[]
}) {
  return (
    <Slip lib={lib} title="Borrowing slip" footer={<>
      <div className="c small">Renew or check due dates in My Library:</div>
      <Qr value={myLibraryUrl()} />
      <div className="c small">{myLibraryUrl()}</div>
      <hr />
    </>}>
      <Row label="Patron" value={patron.fullName} />
      <Row label="Card no." value={<span className="mono">{patron.cardNumber}</span>} />
      <hr />
      {borrowed.map((l, n) => (
        <div key={l.id ?? n} className="item">
          <b>{l.title}</b>
          <Row label={l.barcode} value={<>Due <b>{fmtDate(l.dueAt)}</b></>} />
        </div>
      ))}
      <Row label="Items borrowed now" value={borrowed.length} />
      {otherLoans && otherLoans.length > 0 && <>
        <hr />
        <div className="small">Also on loan:</div>
        {otherLoans.map((l, n) => <Row key={l.id ?? n} label={l.title} value={fmtDate(l.dueAt)} />)}
      </>}
      <div className="c small" style={{ marginTop: 6 }}>Please return items on or before the due date.</div>
    </Slip>
  )
}

export interface ReturnedItem { title: string; barcode: string; patronName?: string; cardNumber?: string; returnedAt: string; fine: number }

/** Return slip: proof of what came back and when, with any overdue fine. */
export function ReturnSlip({ lib, items }: { lib: LibraryProfile; items: ReturnedItem[] }) {
  const fines = items.reduce((s, i) => s + i.fine, 0)
  const who = items.find((i) => i.cardNumber)
  return (
    <Slip lib={lib} title="Return receipt">
      {who && <>
        <Row label="Patron" value={who.patronName} />
        <Row label="Card no." value={<span className="mono">{who.cardNumber}</span>} />
        <hr />
      </>}
      {items.map((i, n) => (
        <div key={n} className="item">
          <b>{i.title}</b>
          <Row label={i.barcode} value={fmtDateTime(i.returnedAt)} />
          {i.fine > 0 && <Row label="Overdue fine" value={fmtMoney(i.fine)} />}
        </div>
      ))}
      <Row label="Items returned" value={items.length} />
      {fines > 0 && <div className="box">Overdue fines added: <b>{fmtMoney(fines)}</b>. Pay at the circulation desk.</div>}
      <div className="c small" style={{ marginTop: 6 }}>Thank you for returning your library materials.</div>
    </Slip>
  )
}

export interface HoldSlipData { holdId: number; title: string; barcode: string; callNumber: string | null; cardNumber: string; initials: string; pickupBy: string | null }

/** Goes inside a book on the hold shelf. Card number and initials only, for privacy. */
export function HoldShelfSlip({ lib, hold }: { lib: LibraryProfile; hold: HoldSlipData }) {
  return (
    <Slip lib={lib} title="Reserved — hold shelf">
      <div className="c">
        <div className="big mono">{hold.cardNumber}</div>
        <div className="big">{hold.initials}</div>
      </div>
      <hr />
      <div className="item"><b>{hold.title}</b></div>
      <Row label="Copy" value={<span className="mono">{hold.barcode}</span>} />
      {hold.callNumber && <Row label="Call no." value={hold.callNumber} />}
      <div className="box c">Hold until <b>{fmtDate(hold.pickupBy)}</b></div>
      <div className="c small">If not picked up by then, release the copy to the next patron or the shelf.</div>
    </Slip>
  )
}

/** For a new patron, or after a PIN is set: card number, PIN (optional) and how to reach My Library. */
export function PatronSlip({ lib, patron, pin }: { lib: LibraryProfile; patron: SlipPatron & { categoryName?: string; expiryDate?: string | null }; pin?: string }) {
  return (
    <Slip lib={lib} title={pin ? 'My Library PIN' : 'Library card'} footer={<>
      <div className="c small">Sign in to My Library with your card number and PIN:</div>
      <Qr value={myLibraryUrl()} />
      <div className="c small">{myLibraryUrl()}</div>
      <hr />
    </>}>
      <Row label="Name" value={patron.fullName} />
      <Row label="Card no." value={<span className="mono big">{patron.cardNumber}</span>} />
      {patron.categoryName && <Row label="Type" value={patron.categoryName} />}
      {patron.expiryDate && <Row label="Valid until" value={fmtDate(patron.expiryDate)} />}
      {pin && <div className="box c">PIN: <span className="big mono">{pin}</span><div className="small">Change it after your first sign-in. Keep this slip private.</div></div>}
    </Slip>
  )
}

export interface ClearanceData {
  reference: string; patronName: string; cardNumber: string; category: string; course: string | null
  purpose: string | null; issuedAt: string; issuedBy: string
}

/** Library clearance: nothing on loan and no unpaid fines on the date issued. */
export function ClearanceSlip({ lib, c }: { lib: LibraryProfile; c: ClearanceData }) {
  return (
    <Slip lib={lib} title="Library clearance" footer={<>
      <div className="c small">Verify this clearance at the library with its reference number.</div>
      <Qr value={c.reference} />
      <hr />
    </>}>
      <div className="c big mono">{c.reference}</div>
      <hr />
      <Row label="Name" value={c.patronName} />
      <Row label="Card no." value={<span className="mono">{c.cardNumber}</span>} />
      <Row label="Type" value={[c.category, c.course].filter(Boolean).join(' · ')} />
      {c.purpose && <Row label="Purpose" value={c.purpose} />}
      <div className="box">This certifies that the person above has <b>no borrowed library materials</b> and
        <b> no unpaid library fines</b> as of {fmtDateTime(c.issuedAt)}.</div>
      <Row label="Issued by" value={c.issuedBy} />
      <div style={{ marginTop: 22 }}>______________________________</div>
      <div className="small">Signature over printed name</div>
    </Slip>
  )
}

export interface CashReportData {
  date: string
  receipts: { receiptNumber: string; paidAt: string; patronName: string; cardNumber: string; amount: number; cashier: string; fines: number }[]
  total: number
  byCashier: { cashier: string; receipts: number; total: number }[]
  waivedCount: number
  waivedTotal: number
}

/** End-of-day fine collections, to reconcile with the cash box. */
export function CashReportSlip({ lib, r }: { lib: LibraryProfile; r: CashReportData }) {
  return (
    <Slip lib={lib} title="Daily collection report">
      <Row label="Date" value={fmtDate(r.date)} />
      <hr />
      {r.receipts.length === 0 ? <div className="c">No payments received.</div> : r.receipts.map((x) => (
        <div key={x.receiptNumber} className="item">
          <Row label={x.receiptNumber} value={fmtMoney(x.amount)} />
          <div className="small">{x.patronName} ({x.cardNumber}) · {x.cashier}</div>
        </div>
      ))}
      <hr />
      <Row label={`TOTAL (${r.receipts.length} receipt${r.receipts.length === 1 ? '' : 's'})`} value={<b>{fmtMoney(r.total)}</b>} />
      {r.byCashier.length > 1 && <>
        <hr />
        {r.byCashier.map((c) => <Row key={c.cashier} label={`${c.cashier} (${c.receipts})`} value={fmtMoney(c.total)} />)}
      </>}
      {r.waivedCount > 0 && <Row label={`Waived (${r.waivedCount}), not collected`} value={fmtMoney(r.waivedTotal)} />}
      <div style={{ marginTop: 22 }}>Prepared by: ______________________</div>
      <div style={{ marginTop: 14 }}>Received by: ______________________</div>
    </Slip>
  )
}

/** Official receipt for fine payments. */
export function OfficialReceiptSlip({ lib, receipt }: { lib: LibraryProfile; receipt: Receipt }) {
  return (
    <Slip lib={lib} title="Official receipt" footer={<>
      {lib.receiptFooter && <div className="c small" style={{ whiteSpace: 'pre-line' }}>{lib.receiptFooter}</div>}
      <hr />
    </>}>
      {lib.receiptHeader && <div className="c small" style={{ whiteSpace: 'pre-line' }}>{lib.receiptHeader}<hr /></div>}
      <Row label="Receipt no." value={<b className="mono">{receipt.receiptNumber}</b>} />
      <Row label="Date" value={fmtDateTime(receipt.paidAt)} />
      <Row label="Patron" value={receipt.patronName} />
      <Row label="Card no." value={<span className="mono">{receipt.cardNumber}</span>} />
      <Row label="Received by" value={receipt.cashier} />
      <hr />
      {receipt.fines.map((f) => <Row key={f.id} label={f.reason} value={fmtMoney(f.amount)} />)}
      <hr />
      <Row label="TOTAL" value={<b>{fmtMoney(receipt.total)}</b>} />
    </Slip>
  )
}
