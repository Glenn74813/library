import { useState } from 'react'
import { api, download, qs } from '../../api/client'
import type { CirculationStats } from '../../api/types'
import { BarList, ErrorAlert, Field, Stat, withFormat } from '../../components/ui'
import { ChartHead, dayLabel } from '../../components/charts'
import { daysAgo, fmtMoney, isoDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

const EXPORTS = [
  { type: 'loans', label: 'Loans in period', ranged: true },
  { type: 'overdue', label: 'Overdue items (now)' },
  { type: 'fines', label: 'All fines & payments' },
  { type: 'collection', label: 'Collection / shelf list' },
  { type: 'patrons', label: 'Patron list' },
]

export function ReportsPage() {
  const [from, setFrom] = useState(daysAgo(29))
  const [to, setTo] = useState(isoDate())
  const [format, setFormat] = useState<'csv' | 'pdf'>('csv')
  const { data, error } = useAsync(() => api<CirculationStats>(`/api/reports/circulation${qs({ from, to })}`), [from, to])

  return (
    <>
      <div className="page-head"><h1>Reports & statistics</h1></div>
      <div className="form-grid card" style={{ marginBottom: 16 }}>
        <Field label="From"><input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="To"><input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      <ErrorAlert error={error} />
      {data && (
        <div className="stack">
          <div className="stats">
            <Stat label="Check-outs" value={data.checkouts} />
            <Stat label="Returns" value={data.returns} />
            <Stat label="Fines collected" value={fmtMoney(data.finesCollected)} />
          </div>
          <div className="grid cols-2">
            <div className="card chart">
              <ChartHead title="Top borrowers" subtitle={`Check-outs · ${dayLabel(from)} – ${dayLabel(to)}`} />
              <BarList data={data.topBorrowers.map((b) => ({ label: b.label, count: b.count }))} />
            </div>
            <div className="card chart">
              <ChartHead title="Most borrowed titles" subtitle={`Check-outs · ${dayLabel(from)} – ${dayLabel(to)}`} />
              <BarList data={data.topTitles.map((t) => ({ label: t.label, count: t.count }))} />
            </div>
          </div>
        </div>
      )}
      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2>Export reports</h2>
          <select className="btn" style={{ width: 'auto' }} value={format} aria-label="Export format"
            onChange={(e) => setFormat(e.target.value as 'csv' | 'pdf')}>
            <option value="csv">Excel (CSV)</option>
            <option value="pdf">PDF</option>
          </select>
        </div>
        <div className="grid cols-3">
          {EXPORTS.map((x) => (
            <button key={x.type} className="btn" onClick={() => download(withFormat(`/api/reports/export/${x.type}${x.ranged ? qs({ from, to }) : ''}`, format), `${x.type}.${format}`)}>
              <Icon name="download" />{x.label}</button>
          ))}
          <button className="btn" onClick={() => download(withFormat(`/api/attendance/export${qs({ from, to })}`, format), `attendance.${format}`)}><Icon name="download" />Attendance in period</button>
          <button className="btn" onClick={() => download(withFormat(`/api/gate/export${qs({ from, to })}`, format), `gate.${format}`)}><Icon name="download" />Gate events in period</button>
        </div>
      </div>
    </>
  )
}
