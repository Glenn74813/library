import { useMemo, useState } from 'react'
import { Icon } from './Icon'

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const MONTH = new Intl.DateTimeFormat('en-PH', { month: 'long', year: 'numeric' })

const iso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const parse = (s: string) => new Date(`${s}T00:00:00`)

/**
 * Month calendar for booking: days in [first, last] that are not unavailable can be picked;
 * fully booked days are shown crossed out and can't be selected.
 */
export function AvailabilityCalendar({ first, last, unavailable, value, onChange }: {
  first: string
  last: string
  unavailable: string[]
  value: string
  onChange: (date: string) => void
}) {
  const blocked = useMemo(() => new Set(unavailable), [unavailable])
  const start = parse(first)
  const end = parse(last)
  const [month, setMonth] = useState(() => new Date(start.getFullYear(), start.getMonth(), 1))
  const canPrev = month > new Date(start.getFullYear(), start.getMonth(), 1)
  const canNext = month < new Date(end.getFullYear(), end.getMonth(), 1)

  // Grid starts on Monday; leading blanks for the first week.
  const lead = (month.getDay() + 6) % 7
  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate()
  const cells: (Date | null)[] = [
    ...Array.from({ length: lead }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) => new Date(month.getFullYear(), month.getMonth(), i + 1)),
  ]
  const freeThisMonth = cells.filter((d) => d && d >= start && d <= end && !blocked.has(iso(d))).length

  return (
    <div className="cal">
      <div className="cal-head">
        <button type="button" className="icon-btn" disabled={!canPrev} aria-label="Previous month"
          onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}><Icon name="chevron-left" /></button>
        <strong>{MONTH.format(month)}</strong>
        <button type="button" className="icon-btn" disabled={!canNext} aria-label="Next month"
          onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}><Icon name="chevron-right" /></button>
      </div>
      <div className="cal-grid" role="grid">
        {WEEKDAYS.map((w) => <div key={w} className="cal-dow">{w}</div>)}
        {cells.map((d, i) => {
          if (!d) return <div key={`b${i}`} />
          const key = iso(d)
          const inRange = d >= start && d <= end
          const full = inRange && blocked.has(key)
          const state = !inRange ? 'out' : full ? 'full' : 'free'
          return (
            <button key={key} type="button" role="gridcell" disabled={state !== 'free'}
              className={`cal-day ${state} ${value === key ? 'selected' : ''}`}
              aria-label={`${d.toDateString()}${full ? ', fully booked' : state === 'free' ? ', available' : ''}`}
              aria-pressed={value === key} onClick={() => onChange(key)}>
              {d.getDate()}
            </button>
          )
        })}
      </div>
      <div className="cal-legend small">
        <span><i className="cal-key free" /> Available</span>
        <span><i className="cal-key full" /> Fully booked / all copies out</span>
        <span><i className="cal-key selected" /> Your pickup date</span>
      </div>
      {freeThisMonth === 0 && <p className="muted small" style={{ margin: 0 }}>No free days this month — try the next month.</p>}
    </div>
  )
}
