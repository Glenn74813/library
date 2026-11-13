import type { Receipt } from '../api/types'
import { fmtMoney } from '../format'
import { Modal } from './ui'
import { Icon } from './Icon'
import { useLibrary } from '../api/library'
import { OfficialReceiptSlip, printRecorded, SlipPreview } from '../print/slips'

/** The official receipt exactly as it prints on the 80 mm receipt printer, with Print. */
export function ReceiptModal({ receipt, onClose }: { receipt: Receipt; onClose: () => void }) {
  const lib = useLibrary()
  const slip = <OfficialReceiptSlip lib={lib} receipt={receipt} />
  return (
    <Modal title={`Official receipt ${receipt.receiptNumber}`} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Close</button>
        <button className="btn primary" onClick={() => void printRecorded({ kind: 'RECEIPT', cardNumber: receipt.cardNumber, reference: receipt.receiptNumber,
          details: fmtMoney(receipt.total) }, slip)}><Icon name="printer" />Print</button></>}>
      <SlipPreview slip={slip} title={`Official receipt ${receipt.receiptNumber}`} />
    </Modal>
  )
}
