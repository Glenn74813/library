import type { Loan } from '../api/types'
import { useLibrary } from '../api/library'
import { fmtDate } from '../format'
import { printReleaseSlip } from '../print/slips'
import { Icon } from './Icon'
import { errorText, useToast } from './Toast'

/** After a hold is released: the loan's details, and its borrowing receipt printed only when staff press the button. */
export function ReleasedReceipt({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const lib = useLibrary()
  const toast = useToast()
  return (
    <div className="alert success row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
      <span><strong>Released:</strong> “{loan.title}” (copy <span className="mono">{loan.barcode}</span>) to {loan.patronName}
        {' '}({loan.cardNumber}), due <strong>{fmtDate(loan.dueAt)}</strong>.</span>
      <span className="row-actions">
        <button className="btn small primary" onClick={() => printReleaseSlip(lib, loan)
          .then(onClose).catch((e) => toast(`The receipt did not print: ${errorText(e)}`, 'error'))}>
          <Icon name="printer" />Print receipt</button>
        <button className="btn small" onClick={onClose}>No receipt</button>
      </span>
    </div>
  )
}
