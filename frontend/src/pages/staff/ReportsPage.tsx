import { useState } from 'react'
import { api, download, qs } from '../../api/client'
import { ITEM_STATUSES, MATERIAL_TYPES, type Category, type CirculationStats, type Course, type LibrarySection } from '../../api/types'
import { BarList, ErrorAlert, Field, Stat, Tabs, withFormat } from '../../components/ui'
import { ChartHead, ColumnChart, PieChart, dayLabel, dayTick, fillDays } from '../../components/charts'
import { daysAgo, fmtMoney, isoDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { hasRole, useSession } from '../../auth/AuthContext'
import { Icon, type IconName } from '../../components/Icon'

type Tab = 'circulation' | 'collection' | 'fines' | 'patrons' | 'gate' | 'ebooks' | 'migration'
type Format = 'xlsx' | 'csv' | 'pdf'
interface Export { label: string; path: string; ranged?: boolean; filtered?: boolean; file: string; icon?: IconName; title?: string }

/** Each sub-module's exports. Ranged ones take the period; filtered ones take the filters above. */
const EXPORTS: Record<Tab, Export[]> = {
  circulation: [
    { label: 'Loans in period', path: '/api/reports/export/loans', ranged: true, filtered: true, file: 'loans' },
    { label: 'Overdue items (now)', path: '/api/reports/export/overdue', filtered: true, file: 'overdue' },
  ],
  collection: [
    { label: 'Collection / shelf list', path: '/api/reports/export/collection', filtered: true, file: 'collection' },
    { label: 'RFID tagging report', path: '/api/reports/export/tagging', filtered: true, file: 'tagging' },
  ],
  fines: [{ label: 'All fines & payments', path: '/api/reports/export/fines', filtered: true, file: 'fines' }],
  patrons: [
    { label: 'Patron list', path: '/api/reports/export/patrons', filtered: true, file: 'patrons' },
    { label: 'Attendance in period', path: '/api/attendance/export', ranged: true, file: 'attendance' },
  ],
  gate: [{ label: 'Gate events in period', path: '/api/gate/export', ranged: true, file: 'gate' }],
  ebooks: [{ label: 'E-book usage in period', path: '/api/reports/export/ebooks', ranged: true, filtered: true, file: 'ebooks' }],
  migration: [{ label: 'Data migration validation report', path: '/api/reports/migration', file: 'migration-validation', icon: 'file-text',
    title: 'Imports, rejected rows, totals, privacy note and sign-off lines' }],
}

const NOTES: Partial<Record<Tab, string>> = {
  collection: 'Every copy with its section, shelf, status and RFID tag. Uses the material type, section and copy status filters.',
  fines: 'Every fine with its payment or waiver. Uses the patron and fine status filters.',
  patrons: 'Registered patrons, and who came into the library in the period.',
  gate: 'Alarms, visitor counts, tamper and power events from the security gates in the period.',
  migration: 'Every import of the library\'s existing records, rejected rows and why, totals now in the system, and sign-off lines.',
}


export function ReportsPage() {
  const [from, setFrom] = useState(daysAgo(29))
  const [to, setTo] = useState(isoDate())
  const [format, setFormat] = useState<Format>('xlsx')
  const [tab, setTab] = useState<Tab>('circulation')
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
      <Tabs value={tab} onChange={setTab} tabs={[
        { id: 'circulation', label: 'Circulation' },
        { id: 'collection', label: 'Collection' },
        { id: 'fines', label: 'Fines & payments' },
        { id: 'patrons', label: 'Patrons & attendance' },
        { id: 'gate', label: 'Security gate' },
        { id: 'ebooks', label: 'E-book usage' },
        ...(canMigrate ? [{ id: 'migration' as const, label: 'Data migration' }] : []),
      ]} />
      <ExportCard exports={EXPORTS[tab]} note={NOTES[tab]} format={format} setFormat={setFormat} from={from} to={to} params={params} />
      {tab === 'circulation' && <>
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
      </>}
      {tab === 'fines' && data && <div className="stats" style={{ marginBottom: 16 }}>
        <Stat label={`Fines collected · ${dayLabel(from)} – ${dayLabel(to)}`} value={fmtMoney(data.finesCollected)} />
      </div>}
      {tab === 'ebooks' && <EbookUsageSection from={from} to={to} />}
    </>
  )
}

/** The current sub-module's downloads, above its figures. */
function ExportCard({ exports, note, format, setFormat, from, to, params }: {
  exports: Export[]; note?: string; format: Format; setFormat: (f: Format) => void
  from: string; to: string; params: Record<string, string>
}) {
  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2>Export reports</h2>
        <select className="btn" style={{ width: 'auto' }} value={format} aria-label="Export format"
          onChange={(e) => setFormat(e.target.value as Format)}>
          <option value="xlsx">Excel (.xlsx)</option>
          <option value="csv">CSV</option>
          <option value="pdf">PDF</option>
        </select>
      </div>
      {note && <p className="muted small">{note}</p>}
      <div className="grid cols-3">
        {exports.map((x) => (
          <button key={x.file} className="btn" title={x.title}
            onClick={() => download(withFormat(`${x.path}${qs({ ...(x.ranged ? { from, to } : {}), ...(x.filtered ? params : {}) })}`, format), `${x.file}.${format}`)}>
            <Icon name={x.icon ?? 'download'} />{x.label}</button>
        ))}
      </div>
    </div>
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
