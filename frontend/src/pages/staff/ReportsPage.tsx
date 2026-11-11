import { useState } from 'react'
import { api, download, qs } from '../../api/client'
import type { CirculationStats } from '../../api/types'
import { BarList, ErrorAlert, Field, Stat, withFormat } from '../../components/ui'
import { ChartHead, ColumnChart, PieChart, dayLabel, dayTick, fillDays } from '../../components/charts'
import { daysAgo, fmtMoney, isoDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

const EXPORTS = [
  { type: 'loans', label: 'Loans in period', ranged: true },
  { type: 'ebooks', label: 'E-book usage in period', ranged: true },
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
        <div className="stack" style={{ marginBottom: 16 }}>
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
      <EbookUsageSection from={from} to={to} />
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

interface EbookUsage {
  views: number; readers: number; titlesRead: number; titlesAvailable: number
  titles: { recordId: number; title: string; authors: string | null; views: number; readers: number; lastViewed: string }[]
  byCourse: { label: string; count: number }[]
  byDay: { label: string; count: number }[]
}

/** School-owned e-books: how often they are opened, by how many patrons, and from which programs. */
function EbookUsageSection({ from, to }: { from: string; to: string }) {
  const { data, error } = useAsync(() => api<EbookUsage>(`/api/reports/ebooks${qs({ from, to })}`), [from, to])
  if (error) return <ErrorAlert error={error} />
  if (!data) return null
  return (
    <div className="stack" style={{ marginBottom: 16 }}>
      <h2 style={{ margin: '8px 0 0' }}>E-book usage</h2>
      <div className="stats">
        <Stat label="Openings" value={data.views} />
        <Stat label="Readers" value={data.readers} />
        <Stat label="Titles read" value={`${data.titlesRead} / ${data.titlesAvailable}`} />
      </div>
      <div className="grid cols-2">
        <div className="card">
          <ColumnChart title="Openings per day" subtitle={`${dayLabel(from)} – ${dayLabel(to)}`} data={fillDays(data.byDay, from, to)}
            format={dayTick} tooltipLabel={dayLabel} total={data.views} totalNote="openings" />
        </div>
        <div className="card">
          <PieChart title="Readers by program" subtitle="Distinct patrons who opened an e-book" data={data.byCourse} unit=" readers" />
        </div>
      </div>
      <div className="card chart">
        <ChartHead title="Most read e-books" subtitle={`Openings · ${dayLabel(from)} – ${dayLabel(to)}`} />
        <BarList data={data.titles.slice(0, 10).map((t) => ({ label: `${t.title} (${t.readers} reader${t.readers === 1 ? '' : 's'})`, count: t.views }))}
          empty="No school-owned e-books were opened in this period" />
      </div>
    </div>
  )
}
