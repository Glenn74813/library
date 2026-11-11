import { useState } from 'react'
import { api } from '../api/client'
import type { BookingCalendar } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { errorText } from './Toast'
import { AvailabilityCalendar } from './AvailabilityCalendar'
import { ErrorAlert, Modal } from './ui'

const fmt = new Intl.DateTimeFormat('en-PH', { weekday: 'short', month: 'short', day: 'numeric' })
const show = (iso: string) => fmt.format(new Date(`${iso}T00:00:00`))

/**
 * "Book for a date": the patron picks a pickup date; until then the copies keep circulating.
 * `calendarPath` returns the dates that are already fully booked for this patron.
 */
export function BookModal({ title, calendarPath, onBook, onClose }: {
  title: string
  calendarPath: string
  onBook: (pickupDate: string) => Promise<void>
  onClose: () => void
}) {
  const { data: cal, error: calError } = useAsync(() => api<BookingCalendar>(calendarPath), [calendarPath])
  const [date, setDate] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const taken = date && cal?.unavailable.includes(date)

  async function submit() {
    if (!date) return setError('Choose a pickup date.')
    if (taken) return setError('That date is fully booked. Please choose another.')
    setBusy(true)
    setError(null)
    try {
      await onBook(date)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={`Book “${title}”`} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" disabled={busy || !cal} onClick={submit}>{busy ? 'Booking…' : 'Book this date'}</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error ?? calError} />
        <p className="muted small" style={{ margin: 0 }}>
          Pick the day you want to collect the book. Until then others may still borrow it; the library makes sure a copy
          is back for you and holds it at the circulation desk from that morning.
        </p>
        {cal && (
          <>
            {cal.copies === 0 ? <div className="alert warn small">The library has no copies that can be booked.</div> : (
              <AvailabilityCalendar first={cal.firstDate} last={cal.lastDate} unavailable={cal.unavailable} value={date} onChange={setDate} />
            )}
            <div className="small">{date ? <>Pickup date: <strong>{show(date)}</strong></> : <span className="muted">Tap an available day.</span>}</div>
          </>
        )}
      </div>
    </Modal>
  )
}
