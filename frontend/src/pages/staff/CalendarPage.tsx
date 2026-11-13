import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../../api/client'
import { hasRole, useSession } from '../../auth/AuthContext'
import { Icon } from '../../components/Icon'
import { errorText, useToast } from '../../components/Toast'
import { Field, Table } from '../../components/ui'
import { fmtDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'

interface DayHours { weekday: number; openTime: string | null; closeTime: string | null }
interface ClosedDay { day: string; reason: string }

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const hhmm = (t: string | null) => (t ? t.slice(0, 5) : '')

/**
 * Opening hours and closed days. Due dates and pickup deadlines skip closed days, fines count only
 * open days, closed days can't be booked, and open visits are timed out at closing time.
 */
export function CalendarPage() {
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const toast = useToast()
  const { data: pub } = useAsync(() => api<{ hours: DayHours[] }>('/api/opac/calendar', { anonymous: true }), [])
  const { data: closed, reload } = useAsync(() => api<ClosedDay[]>('/api/calendar/closed-days'), [])
  const [week, setWeek] = useState<DayHours[]>([])
  const [day, setDay] = useState('')
  const [reason, setReason] = useState('')

  useEffect(() => { if (pub) setWeek(pub.hours.map((h) => ({ ...h, openTime: hhmm(h.openTime), closeTime: hhmm(h.closeTime) }))) }, [pub])
  const set = (wd: number, patch: Partial<DayHours>) => setWeek((w) => w.map((h) => (h.weekday === wd ? { ...h, ...patch } : h)))

  async function saveHours() {
    try {
      await api('/api/calendar/hours', { method: 'PUT', body: week.map((h) => ({ weekday: h.weekday, openTime: h.openTime || null, closeTime: h.closeTime || null })) })
      toast('Opening hours saved')
    } catch (e) { toast(errorText(e), 'error') }
  }

  async function addClosed(e: FormEvent) {
    e.preventDefault()
    try {
      await api('/api/calendar/closed-days', { body: { day, reason } })
      setDay(''); setReason('')
      toast('Closed day added')
      void reload()
    } catch (err) { toast(errorText(err), 'error') }
  }

  async function removeClosed(d: string) {
    try { await api(`/api/calendar/closed-days/${d}`, { method: 'DELETE' }); void reload() } catch (e) { toast(errorText(e), 'error') }
  }

  return (
    <>
      <div className="page-head"><h1>Library calendar</h1></div>
      <p className="muted" style={{ marginTop: -8 }}>Due dates and pickup deadlines move to the next open day, overdue fines count only open days,
        closed days can't be booked, and visitors who forget to tap out are timed out at closing time.</p>
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>Opening hours</h2>
          <fieldset disabled={!canEdit} style={{ border: 0, padding: 0, margin: 0 }} className="stack">
            {week.map((h) => {
              const open = !!(h.openTime && h.closeTime) || h.openTime !== '' || h.closeTime !== ''
              return (
                <div key={h.weekday} className="hours-row">
                  <label className="check"><input type="checkbox" checked={open}
                    onChange={(e) => set(h.weekday, e.target.checked ? { openTime: '08:00', closeTime: '17:00' } : { openTime: '', closeTime: '' })} />
                    <strong>{DAYS[h.weekday - 1]}</strong></label>
                  {open ? <span className="row" style={{ flexWrap: 'nowrap' }}>
                    <input type="time" aria-label={`${DAYS[h.weekday - 1]} opens`} value={h.openTime ?? ''} onChange={(e) => set(h.weekday, { openTime: e.target.value })} />
                    <span className="muted">to</span>
                    <input type="time" aria-label={`${DAYS[h.weekday - 1]} closes`} value={h.closeTime ?? ''} onChange={(e) => set(h.weekday, { closeTime: e.target.value })} />
                  </span> : <span className="muted">Closed</span>}
                </div>
              )
            })}
          </fieldset>
          {canEdit && <div className="row end"><button className="btn primary" onClick={saveHours}>Save hours</button></div>}
        </div>
        <div className="card stack">
          <h2 style={{ margin: 0 }}>Closed days & holidays</h2>
          {canEdit && (
            <form className="form-grid" onSubmit={addClosed}>
              <Field label="Date"><input type="date" value={day} onChange={(e) => setDay(e.target.value)} required /></Field>
              <Field label="Reason" hint="e.g. Bonifacio Day, Foundation Day"><input value={reason} maxLength={120} onChange={(e) => setReason(e.target.value)} required /></Field>
              <div className="row end span-all"><button className="btn primary"><Icon name="plus" />Add closed day</button></div>
            </form>
          )}
          <Table rows={closed} rowKey={(c) => c.day} empty="No closed days scheduled" columns={[
            { header: 'Date', render: (c) => <strong>{fmtDate(c.day)}</strong> },
            { header: 'Reason', render: (c) => c.reason },
            { header: 'Actions', render: (c) => canEdit && <button className="btn small danger" onClick={() => removeClosed(c.day)}>Remove</button> },
          ]} />
        </div>
      </div>
    </>
  )
}
