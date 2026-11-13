import { useState } from 'react'
import { api, download, qs } from '../../api/client'
import { ITEM_STATUSES, MATERIAL_TYPES, type Category, type CirculationStats, type Course, type LibrarySection } from '../../api/types'
import { BarList, ErrorAlert, Field, Stat, withFormat } from '../../components/ui'
import { ChartHead, ColumnChart, PieChart, dayLabel, dayTick, fillDays } from '../../components/charts'
import { daysAgo, fmtMoney, isoDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { hasRole, useSession } from '../../auth/AuthContext'
import { Icon } from '../../components/Icon'

const EXPORTS = [
  { type: 'loans', label: 'Loans in period', ranged: true },
  { type: 'ebooks', label: 'E-book usage in period', ranged: true },
  { type: 'overdue', label: 'Overdue items (now)' },
  { type: 'fines', label: 'All fines & payments' },
  { type: 'collection', label: 'Collection / shelf list' },
  { type: 'tagging', label: 'RFID tagging report' },
  { type: 'patrons', label: 'Patron list' },
]

export function ReportsPage() {
  const [from, setFrom] = useState(daysAgo(29))
  const [to, setTo] = useState(isoDate())
  const [format, setFormat] = useState<'xlsx' | 'csv' | 'pdf'>('xlsx')
  const canMigrate = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  // Optional filters: who (category, course), what (material type, section) and status. They narrow the
  // figures below and every export; each report applies the ones that fit it.
  const [flt, setFlt] = useState({ categoryId: '', courseId: '', materialType: '', section: '', itemStatus: '', fineStatus: '' })
  const setF = (k: keyof typeof flt) => (e: { target: { value: string } }) => setFlt((x) => ({ ...x, [k]: e.target.value }))
  const params = Object.fromEntries(Object.entries(flt).filter(([, v]) => v)) as Record<string, string>
  const anyFilter = Object.keys(params).length > 0
  const { data: categories } = useAsync(() => api<Category[]>('/api/patron-categories'), [])
  const { data: courses } = useAsync(() => api<Course[]>('/api/courses'), [])
  const { data: sections } = useAsync(() => api<LibrarySection[]>('/api/sections'), [])
  const { data, error } = useAsync(() => api<CirculationStats>(`/api/reports/circulation${qs({ from, to,
    categoryId: flt.categoryId || undefined, courseId: flt.courseId || undefined, materialType: flt.materialType || undefined,
    section: flt.section || undefined })}`), [from, to, flt.categoryId, flt.courseId, flt.materialType, flt.section])

  return (
    <>
      <div className="page-head"><h1>Reports & statistics</h1></div>
      <div className="form-grid card" style={{ marginBottom: 16 }}>
        <Field label="From"><input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="To"><input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
        <Field label="Patron category"><select value={flt.categoryId} onChange={setF('categoryId')}>
          <option value="">All</option>{categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
        <Field label="Course / program"><select value={flt.courseId} onChange={setF('courseId')}>
          <option value="">All</option>{courses?.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></Field>
        <Field label="Material type"><select value={flt.materialType} onChange={setF('materialType')}>
          <option value="">All</option>{MATERIAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
        <Field label="Section"><select value={flt.section} onChange={setF('section')}>
          <option value="">All</option>{sections?.map((x) => <option key={x.id} value={x.name}>{x.name}</option>)}</select></Field>
        <Field label="Copy status" hint="Collection and tagging reports"><select value={flt.itemStatus} onChange={setF('itemStatus')}>
          <option value="">All</option>{ITEM_STATUSES.map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
        <Field label="Fine status" hint="Fines report"><select value={flt.fineStatus} onChange={setF('fineStatus')}>
          <option value="">All</option>{(['UNPAID', 'PAID', 'WAIVED'] as const).map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
        {anyFilter && <div className="span-all row" style={{ justifyContent: 'space-between' }}>
          <span className="muted small">Filters apply to the figures and charts below and to the exports.</span>
          <button className="btn small" onClick={() => setFlt({ categoryId: '', courseId: '', materialType: '', section: '', itemStatus: '', fineStatus: '' })}>Clear filters</button>
        </div>}
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
            onChange={(e) => setFormat(e.target.value as 'xlsx' | 'csv' | 'pdf')}>
            <option value="xlsx">Excel (.xlsx)</option>
            <option value="csv">CSV</option>
            <option value="pdf">PDF</option>
          </select>
        </div>
        <div className="grid cols-3">
          {EXPORTS.map((x) => (
            <button key={x.type} className="btn" onClick={() => download(withFormat(`/api/reports/export/${x.type}${qs({ ...(x.ranged ? { from, to } : {}), ...params })}`, format), `${x.type}.${format}`)}>
              <Icon name="download" />{x.label}</button>
          ))}
          <button className="btn" onClick={() => download(withFormat(`/api/attendance/export${qs({ from, to })}`, format), `attendance.${format}`)}><Icon name="download" />Attendance in period</button>
          <button className="btn" onClick={() => download(withFormat(`/api/gate/export${qs({ from, to })}`, format), `gate.${format}`)}><Icon name="download" />Gate events in period</button>
          {canMigrate && <button className="btn" title="Imports, rejected rows, totals, privacy note and sign-off lines"
            onClick={() => download(withFormat('/api/reports/migration', format), `migration-validation.${format}`)}>
            <Icon name="file-text" />Data migration validation report</button>}
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
