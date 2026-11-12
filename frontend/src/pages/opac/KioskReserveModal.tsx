import { useState, type FormEvent } from 'react'
import { api } from '../../api/client'
import type { BookingCalendar } from '../../api/types'
import { AvailabilityCalendar } from '../../components/AvailabilityCalendar'
import { Icon } from '../../components/Icon'
import { applyKey, OnScreenKeyboard } from '../../components/OnScreenKeyboard'
import { errorText } from '../../components/Toast'
import { ErrorAlert, Field, Modal } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'

interface Reserved { title: string; message: string }

const fmt = new Intl.DateTimeFormat('en-PH', { weekday: 'short', month: 'short', day: 'numeric' })

/**
 * Catalog kiosk: borrow (a copy is set aside at the desk), join the line, or book a date, after scanning
 * the library card or typing the card number (and PIN, when the library requires one). Nothing stays
 * signed in: each request sends the card again, and closing the window forgets it.
 */
export function KioskReserveModal({ recordId, title, available, onClose }: {
  recordId: number; title: string; available: boolean; onClose: () => void
}) {
  const { data: config } = useAsync(() => api<{ requirePin: boolean }>('/api/opac/reserve/config', { anonymous: true }), [])
  const [card, setCard] = useState('')
  const [pin, setPin] = useState('')
  const [focus, setFocus] = useState<'card' | 'pin'>('card')
  const [name, setName] = useState<string | null>(null)
  const [step, setStep] = useState<'card' | 'choose' | 'date' | 'done'>('card')
  const [cal, setCal] = useState<BookingCalendar | null>(null)
  const [date, setDate] = useState('')
  const [done, setDone] = useState<Reserved | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const patron = { card: card.trim(), pin: pin || undefined }

  async function run(fn: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try { await fn() } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }

  const identify = (e?: FormEvent) => {
    e?.preventDefault()
    if (!card.trim()) return setError('Scan your library card or type your card number.')
    if (config?.requirePin && !pin) { setFocus('pin'); return setError('Enter your PIN.') }
    void run(async () => {
      const r = await api<{ firstName: string }>('/api/opac/reserve/identify', { anonymous: true, body: patron })
      setName(r.firstName)
      setStep('choose')
    })
  }

  const reserve = (pickupDate?: string) => run(async () => {
    setDone(await api<Reserved>('/api/opac/reserve', { anonymous: true, body: { patron, recordId, pickupDate } }))
    setStep('done')
  })

  const openCalendar = () => run(async () => {
    setCal(await api<BookingCalendar>('/api/opac/reserve/calendar', { anonymous: true, body: { patron, recordId } }))
    setStep('date')
  })

  const key = (k: string) => (focus === 'pin' ? setPin((v) => applyKey(v, k).replace(/\D/g, '')) : setCard((v) => applyKey(v, k)))

  return (
    <Modal title={step === 'done' ? 'All set' : `Borrow or reserve “${title}”`} onClose={onClose} footer={<>
      {step === 'card' && <><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy} onClick={() => identify()}>{busy ? 'Checking…' : 'Continue'}</button></>}
      {step === 'choose' && <button className="btn" onClick={onClose}>Cancel</button>}
      {step === 'date' && <><button className="btn" onClick={() => setStep('choose')}>Back</button>
        <button className="btn primary" disabled={busy || !date} onClick={() => void reserve(date)}>{busy ? 'Booking…' : 'Book this date'}</button></>}
      {step === 'done' && <button className="btn primary" onClick={onClose}>Done</button>}
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />

        {step === 'card' && <form className="stack" onSubmit={identify}>
          <p className="muted" style={{ margin: 0 }}>Scan your library card, or type your card number{config?.requirePin ? ' and PIN' : ''}.</p>
          <Field label="Card number">
            <input value={card} onChange={(e) => setCard(e.target.value)} onFocus={() => setFocus('card')} autoFocus
              autoComplete="off" inputMode="none" />
          </Field>
          {config?.requirePin && <Field label="PIN">
            <input type="password" value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} onFocus={() => setFocus('pin')}
              autoComplete="off" inputMode="none" maxLength={8} />
          </Field>}
          {/* A card scanner types the number and presses Enter. */}
          <button type="submit" hidden />
          <OnScreenKeyboard onKey={key} onEnter={() => identify()} enterLabel="Continue" />
        </form>}

        {step === 'choose' && <>
          <p style={{ margin: 0 }}>Hi <strong>{name}</strong>! What would you like to do?</p>
          <button className="btn primary big block" disabled={busy} onClick={() => void reserve()}>
            <Icon name="bookmark" />{available ? 'Borrow now — set a copy aside for me at the desk' : 'Reserve — I\'ll take the next copy returned'}</button>
          <button className="btn big block" disabled={busy} onClick={() => void openCalendar()}>
            <Icon name="calendar" />Book it for a later date</button>
          <p className="muted small" style={{ margin: 0 }}>
            {available ? 'Staff take a copy off the shelf and keep it at the circulation desk under your name.'
              : 'You will get a notice when a copy is waiting for you at the circulation desk.'}
          </p>
        </>}

        {step === 'date' && cal && <>
          <p className="muted small" style={{ margin: 0 }}>Pick the day you want to collect it. The library holds a copy for you from that morning.</p>
          {cal.copies === 0 ? <div className="alert warn small">The library has no copies that can be booked.</div>
            : <AvailabilityCalendar first={cal.firstDate} last={cal.lastDate} unavailable={cal.unavailable} value={date} onChange={setDate} />}
          <div className="small">{date ? <>Pickup date: <strong>{fmt.format(new Date(`${date}T00:00:00`))}</strong></> : <span className="muted">Tap an available day.</span>}</div>
        </>}

        {step === 'done' && done && <div className="alert success">
          <strong>{done.title}</strong><br />{done.message}
          <div className="small" style={{ marginTop: 6 }}>Bring your library card. You can also see this in My Library.</div>
        </div>}
      </div>
    </Modal>
  )
}
