import { useState } from 'react'
import { api, download, qs } from '../../api/client'
import type { GateEvent, GateEventType, GateSummary, Page } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Pager, Stat, StatusBadge, Table } from '../../components/ui'
import { daysAgo, fmtDateTime, isoDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

const TYPES: GateEventType[] = ['ALARM', 'TAMPER', 'POWER_LOSS', 'POWER_RESTORED', 'VISITOR', 'EMERGENCY_ON', 'EMERGENCY_OFF', 'BROADCAST']

export function GatePage() {
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [type, setType] = useState('')
  const [from, setFrom] = useState(daysAgo(6))
  const [to, setTo] = useState(isoDate())
  const [page, setPage] = useState(0)
  const [message, setMessage] = useState('')
  const toast = useToast()
  const { data: summary, reload: reloadSummary } = useAsync(() => api<GateSummary>('/api/gate/summary'), [])
  const { data: events, error, reload } = useAsync(
    () => api<Page<GateEvent>>(`/api/gate/events${qs({ type, from, to, page })}`), [type, from, to, page])

  async function setMode(emergencyMode: boolean, alarmEnabled: boolean) {
    if (emergencyMode && !summary?.emergencyMode && !confirm('Turn ON emergency/evacuation mode? Gate alarms will be disabled.')) return
    try {
      await api('/api/gate/settings', { method: 'PUT', body: { emergencyMode, alarmEnabled } })
      toast('Gate settings updated')
      void reloadSummary(); void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function broadcast(text: string) {
    if (!text.trim()) return
    try {
      await api('/api/gate/broadcast', { body: { message: text } })
      toast('Announcement sent to library displays')
      setMessage('')
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Security gate</h1>
        <button className="btn" onClick={() => download(`/api/gate/export${qs({ from, to })}`, 'gate-events.csv')}><Icon name="download" />Export log</button>
      </div>
      {summary?.emergencyMode && <div className="alert error" style={{ marginBottom: 16 }}><strong>EMERGENCY MODE IS ON</strong> — gate alarms are suppressed and the gate never blocks people.</div>}
      {summary && (
        <div className="stats" style={{ marginBottom: 16 }}>
          <Stat label="Alarms today" value={summary.alarmsToday} tone={summary.alarmsToday ? 'bad' : undefined} />
          <Stat label="Visitors today" value={summary.visitorsToday} />
          <Stat label="Tamper events" value={summary.tamperToday} tone={summary.tamperToday ? 'bad' : undefined} />
          <Stat label="Power-loss events" value={summary.powerLossToday} tone={summary.powerLossToday ? 'warn' : undefined} />
        </div>
      )}
      <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <div className="card stack">
          <h2>Gate control & device health</h2>
          {summary?.gates.length ? summary.gates.map((g) => (
            <div key={g.gateId} className="row"><strong>{g.gateId}</strong><span className={`badge ${g.online ? 'ok' : 'bad'}`}>{g.online ? 'Online' : 'Offline'}</span>
              <span className="muted small">last seen {fmtDateTime(g.lastSeen)}</span></div>
          )) : <p className="muted small">No gate has reported yet.</p>}
          {summary && canEdit && (
            <div className="row actions-stretch">
              <button className={`btn ${summary.emergencyMode ? '' : 'danger solid'}`} onClick={() => setMode(!summary.emergencyMode, summary.alarmEnabled)}>
                <Icon name="alert-octagon" />{summary.emergencyMode ? 'End emergency mode' : 'Emergency / evacuation mode'}</button>
              <button className="btn" onClick={() => setMode(summary.emergencyMode, !summary.alarmEnabled)}>
                {summary.alarmEnabled ? 'Disable alarm' : 'Enable alarm'}</button>
            </div>
          )}
        </div>
        <div className="card stack">
          <h2>Announcement broadcast</h2>
          <p className="muted small">Shows on every library kiosk/display and is read aloud (text-to-speech).</p>
          <textarea value={message} onChange={(e) => setMessage(e.target.value)} placeholder="e.g. Earthquake drill: please proceed calmly to the nearest exit." />
          <div className="row actions-stretch">
            <button className="btn" onClick={() => broadcast('Attention please. This is an emergency. Please leave the library calmly using the nearest exit.')}>Evacuation preset</button>
            <button className="btn primary" onClick={() => broadcast(message)}><Icon name="volume-2" />Broadcast</button>
          </div>
        </div>
      </div>
      <div className="card">
        <h2>Event log</h2>
        <div className="form-grid" style={{ marginBottom: 12 }}>
          <Field label="Event"><select value={type} onChange={(e) => { setType(e.target.value); setPage(0) }}>
            <option value="">All events</option>{TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
          <Field label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        </div>
        <ErrorAlert error={error} />
        <Table rows={events?.content} rowKey={(e) => e.id} empty="No events" columns={[
          { header: 'Time', render: (e) => fmtDateTime(e.occurredAt) },
          { header: 'Gate', render: (e) => e.gateId },
          { header: 'Event', render: (e) => <StatusBadge status={e.type} /> },
          { header: 'Item', render: (e) => e.title ? <>{e.title}<div className="muted small mono">{e.barcode}</div></> : e.epc ?? '—' },
          { header: 'Details', render: (e) => e.visitorCount != null ? `${e.visitorCount} visitor(s)` : e.message ?? '—' },
        ]} />
        <Pager page={events} onPage={setPage} />
      </div>
    </>
  )
}
