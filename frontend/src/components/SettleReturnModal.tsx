import { useState } from 'react'
import { api } from '../api/client'
import type { CheckinPreview, CheckinResult } from '../api/types'
import { hasRole, useSession } from '../auth/AuthContext'
import { fmtDate, fmtMoney } from '../format'
import { Icon } from './Icon'
import { errorText } from './Toast'
import { ErrorAlert, Field, Modal } from './ui'

/**
 * A return that has fines tied to the borrowing (the overdue fine it charges now, plus charges such as
 * damage already linked to it). The return completes only when they are paid, on one receipt, or waived
 * by a librarian or administrator; cancelling leaves the book on loan.
 */
export function SettleReturnModal({ item, preview, onDone, onCancel }: {
  item: string; preview: CheckinPreview; onDone: (r: CheckinResult) => void; onCancel: () => void
}) {
  const canWaive = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [waiving, setWaiving] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const l = preview.loan

  async function settle(how: 'PAY' | 'WAIVE') {
    setBusy(true)
    setError(null)
    try {
      onDone(await api<CheckinResult>('/api/circulation/checkin', { body: { item, settle: how, waiveReason: how === 'WAIVE' ? reason : undefined } }))
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="Settle fines to complete the return" onClose={onCancel} footer={<>
      <button className="btn" onClick={onCancel} disabled={busy}>Cancel (keep on loan)</button>
      <span className="spacer" />
      {canWaive && !waiving && <button className="btn" disabled={busy} onClick={() => setWaiving(true)}>Waive…</button>}
      {waiving
        ? <button className="btn danger" disabled={busy || !reason.trim()} onClick={() => void settle('WAIVE')}>Waive and return</button>
        : <button className="btn primary" disabled={busy} onClick={() => void settle('PAY')}><Icon name="credit-card" />Receive {fmtMoney(preview.total)} and return</button>}
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        {l && <div>
          <strong>{l.title}</strong> <span className="muted small mono">{l.barcode}</span>
          <div className="muted small">{l.patronName} ({l.cardNumber}) · due {fmtDate(l.dueAt)}{l.overdue && ` · ${l.overdueDays} day(s) late`}</div>
        </div>}
        <p className="small" style={{ margin: 0 }}>This book can only be returned once the fines for this borrowing are cleared.</p>
        <table className="data">
          <tbody>
            {preview.overdueFine > 0 && <tr><td>Overdue fine{l ? ` (${l.overdueDays} day(s) late)` : ''}</td><td className="nowrap" style={{ textAlign: 'right' }}>{fmtMoney(preview.overdueFine)}</td></tr>}
            {preview.charges.map((c) => <tr key={c.id}><td>{c.reason}</td><td className="nowrap" style={{ textAlign: 'right' }}>{fmtMoney(c.amount)}</td></tr>)}
            <tr><td><strong>Total to settle</strong></td><td className="nowrap" style={{ textAlign: 'right' }}><strong>{fmtMoney(preview.total)}</strong></td></tr>
          </tbody>
        </table>
        {waiving && <Field label="Reason for waiving *"><input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} autoFocus /></Field>}
      </div>
    </Modal>
  )
}
