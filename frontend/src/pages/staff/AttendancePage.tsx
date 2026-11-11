import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useDebounced } from '../../hooks/useDebounced'
import { api, qs } from '../../api/client'
import type { AttendanceLog, AttendanceStats, Page } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Pager, Stat, StatusBadge, Table, Tabs, ExportMenu } from '../../components/ui'
import { ColumnChart, PieChart, dayLabel, dayTick, fillDays, fillHours, hourLabel } from '../../components/charts'
import { daysAgo, fmtDateTime, fmtTime, isoDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'

export function AttendancePage() {
  const [tab, setTab] = useState<'stats' | 'logs' | 'settings'>('stats')
  const [from, setFrom] = useState(daysAgo(29))
  const [to, setTo] = useState(isoDate())
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')

  return (
    <>
      <div className="page-head">
        <h1>Library attendance</h1>
        <ExportMenu path={`/api/attendance/export${qs({ from, to })}`} name="attendance" label="Download report…" />
      </div>
      <div className="form-grid card" style={{ marginBottom: 16 }}>
        <Field label="From"><input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="To"><input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      <Tabs value={tab} onChange={setTab} tabs={[
        { id: 'stats', label: 'Statistics' }, { id: 'logs', label: 'Logs' }, { id: 'settings', label: 'Kiosk settings' }]} />
      {tab === 'stats' && <StatsTab from={from} to={to} />}
      {tab === 'logs' && <LogsTab from={from} to={to} />}
      {tab === 'settings' && <SettingsTab canEdit={canEdit} />}
    </>
  )
}

function StatsTab({ from, to }: { from: string; to: string }) {
  const { data, error } = useAsync(() => api<AttendanceStats>(`/api/attendance/stats${qs({ from, to })}`), [from, to])
  if (error) return <ErrorAlert error={error} />
  if (!data) return <p className="muted">Loading…</p>
  const period = from === to ? dayLabel(from) : `${dayLabel(from)} – ${dayLabel(to)}`
  const days = fillDays(data.daily, from, to)
  const hours = fillHours(data.hourly)
  const peak = data.hourly.length ? hours.reduce((a, b) => (b.count > a.count ? b : a)) : null
  return (
    <div className="stack">
      <div className="stats">
        <Stat label="Total visits" value={data.totalVisits} />
        <Stat label="Unique patrons" value={data.uniquePatrons} />
        <Stat label="Inside now" value={data.currentlyInside} />
      </div>
      <div className="grid cols-2">
        <div className="card"><PieChart title="Top 5 programs / courses" subtitle={period} data={data.topCourses} unit=" visit(s)" /></div>
        <div className="card"><PieChart title="Top 5 patron categories" subtitle={period} data={data.topCategories} unit=" visit(s)" /></div>
        <div className="card">
          <ColumnChart title="Visits per day" subtitle={period} data={days} format={dayTick} tooltipLabel={dayLabel}
            total={data.totalVisits.toLocaleString('en-PH')} totalNote={`${dayLabel(to)}: ${days[days.length - 1]?.count ?? 0}`} unit=" visit(s)" />
        </div>
        <div className="card">
          <ColumnChart title="Busiest hours" subtitle={`Time-ins by hour · ${period}`} data={hours} format={hourLabel} emphasis="max"
            total={peak ? hourLabel(peak.label) : '—'} totalNote="peak hour" unit=" visit(s)" />
        </div>
      </div>
    </div>
  )
}

function LogsTab({ from, to }: { from: string; to: string }) {
  const [page, setPage] = useState(0)
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const { data, error } = useAsync(() => api<Page<AttendanceLog>>(`/api/attendance/logs${qs({ from, to, q: dq, page })}`), [from, to, dq, page])
  return (
    <div className="card">
      <input type="search" placeholder="Filter by patron name or card number…" value={q} style={{ marginBottom: 12 }}
        onChange={(e) => { setQ(e.target.value); setPage(0) }} />
      <ErrorAlert error={error} />
      <Table rows={data?.content} rowKey={(l) => l.id} empty="No visits in this period" columns={[
        { header: 'Patron', render: (l) => <><Link to={`/staff/patrons/${l.patronId}`}>{l.patronName}</Link><div className="muted small mono">{l.cardNumber}</div></> },
        { header: 'Course', render: (l) => l.course ?? '—' },
        { header: 'Category', render: (l) => l.category },
        { header: 'Time in', render: (l) => fmtDateTime(l.timeIn) },
        { header: 'Time out', render: (l) => l.timeOut ? fmtTime(l.timeOut) : <span className="badge ok">Inside</span> },
        { header: 'Via', render: (l) => <StatusBadge status={l.method} /> },
      ]} />
      <Pager page={data} onPage={setPage} />
    </div>
  )
}

function SettingsTab({ canEdit }: { canEdit: boolean }) {
  const { data } = useAsync(() => api<Record<string, string>>('/api/attendance/settings'), [])
  const [s, setS] = useState<Record<string, string>>({})
  const toast = useToast()
  useEffect(() => { if (data) setS(data) }, [data])
  const set = (k: string, v: string) => setS((x) => ({ ...x, [k]: v }))

  async function save() {
    try {
      await api('/api/attendance/settings', { method: 'PUT', body: s })
      toast('Kiosk settings saved — kiosks update within a minute')
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <div className="card stack">
      <fieldset disabled={!canEdit} style={{ border: 0, padding: 0, margin: 0 }} className="form-grid">
        <Field label="Kiosk mode">
          <select value={s.mode ?? 'ATTENDANCE'} onChange={(e) => set('mode', e.target.value)}>
            <option value="ATTENDANCE">Attendance monitoring (time-in / time-out)</option>
            <option value="STANDBY">Stand-by: information board / video</option>
          </select>
        </Field>
        <Field label="Tap behaviour" as="div">
          <label className="check"><input type="checkbox" checked={s.singleTap === 'true'}
            onChange={(e) => set('singleTap', String(e.target.checked))} /> Single-tap mode (time-in only)</label>
        </Field>
        <Field label="Entry message" className="span-all"><input value={s.entryMessage ?? ''} onChange={(e) => set('entryMessage', e.target.value)} /></Field>
        <Field label="Exit message" className="span-all"><input value={s.exitMessage ?? ''} onChange={(e) => set('exitMessage', e.target.value)} /></Field>
        <Field label="Information board messages" hint="One message per line; they rotate on the kiosk" className="span-all">
          <textarea value={(s.standbyMessages ?? '').split('|').join('\n')}
            onChange={(e) => set('standbyMessages', e.target.value.split('\n').join('|'))} />
        </Field>
        <Field label="Stand-by video URL" hint="MP4 file URL played in stand-by mode (optional)" className="span-all">
          <input type="url" value={s.videoUrl ?? ''} onChange={(e) => set('videoUrl', e.target.value)} />
        </Field>
      </fieldset>
      {canEdit ? <div className="row end"><button className="btn primary" onClick={save}>Save settings</button></div>
        : <p className="muted small">Only librarians can change kiosk settings.</p>}
    </div>
  )
}
