import type { Receipt } from '../api/types'
import { fmtDateTime, fmtMoney } from '../format'
import { Modal } from './ui'
import { Icon } from './Icon'
import { Logo } from './Logo'

/** Printable payment receipt sized for an 80 mm thermal printer. */
export function ReceiptModal({ receipt, onClose }: { receipt: Receipt; onClose: () => void }) {
  return (
    <Modal title="Official receipt" onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Close</button>
        <button className="btn primary" onClick={() => window.print()}><Icon name="printer" />Print</button></>}>
      <div className="print-receipt stack" style={{ gap: 6 }}>
        <div style={{ textAlign: 'center' }}>
          <Logo />
          <strong>ZSCMST Learning Resource Center</strong><br />
          <span className="small">Library fines receipt</span>
        </div>
        <div className="small">
          Receipt No: <strong className="mono">{receipt.receiptNumber}</strong><br />
          Date: {fmtDateTime(receipt.paidAt)}<br />
          Patron: {receipt.patronName} ({receipt.cardNumber})<br />
          Received by: {receipt.cashier}
        </div>
        <table className="small" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {receipt.fines.map((f) => (
              <tr key={f.id}>
                <td style={{ padding: '4px 0' }}>{f.reason}</td>
                <td className="right nowrap" style={{ padding: '4px 0' }}>{fmtMoney(f.amount)}</td>
              </tr>
            ))}
            <tr>
              <td style={{ borderTop: '1px dashed currentColor', paddingTop: 6 }}><strong>TOTAL</strong></td>
              <td className="right" style={{ borderTop: '1px dashed currentColor', paddingTop: 6 }}>
                <strong>{fmtMoney(receipt.total)}</strong></td>
            </tr>
          </tbody>
        </table>
        <div className="small" style={{ textAlign: 'center' }}>Thank you!</div>
      </div>
    </Modal>
  )
}
