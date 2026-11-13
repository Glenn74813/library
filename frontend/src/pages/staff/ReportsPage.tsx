import { useState } from 'react'
import { api, download, qs } from '../../api/client'
import { ITEM_STATUSES, MATERIAL_TYPES, type Category, type CirculationStats, type Course, type LibrarySection } from '../../api/types'
import { BarList, ErrorAlert, Field, Stat, Table, Tabs, withFormat } from '../../components/ui'
import { ChartHead, ColumnChart, PieChart, dayLabel, dayTick, fillDays } from '../../components/charts'
import { daysAgo, fmtMoney, isoDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { hasRole, useSession } from '../../auth/AuthContext'
import { Icon } from '../../components/Icon'

type Tab = 'circulation' | 'collection' | 'fines' | 'patrons' | 'gate' | 'ebooks' | 'migration'
type Format = 'xlsx' | 'csv' | 'pdf'
/** A filter a report understands; 'period' is the From / To dates. */
type FilterKey = 'period' | 'categoryId' | 'courseId' | 'materialType' | 'section' | 'itemStatus' | 'fineStatus'
type Filters = Record<Exclude<FilterKey, 'period'>, string>

interface Report {
  label: string
  /** Download URL; the same URL + "/preview" gives the first rows shown on the page. */
  path: string
  file: string
  /** What the report lists, in one line. */
  about: string
  /** The filters this report applies. Only these are sent, and only these are shown on its tab. */
  uses: FilterKey[]
}

const WHO: FilterKey[] = ['categoryId', 'courseId']
const WHAT: FilterKey[] = ['materialType', 'section']

/** Each sub-module, its reports, and which filters each report really uses. */
const TABS: { id: Tab; label: string; reports: Report[] }[] = [
  { id: 'circulation', label: 'Circulation', reports: [
    { label: 'Loans in period', path: '/api/reports/export/loans', file: 'loans', uses: ['period', ...WHO, ...WHAT],
      about: 'Every check-out made in the period, with due and return dates.' },
    { label: 'Overdue items', path: '/api/reports/export/overdue', file: 'overdue', uses: [...WHO, ...WHAT],
      about: 'Copies still out and past their due date right now (the period does not apply).' },
  ] },
  { id: 'collection', label: 'Collection', reports: [
    { label: 'Collection / shelf list', path: '/api/reports/export/collection', file: 'collection', uses: [...WHAT, 'itemStatus'],
      about: 'Every copy with its section, aisle, shelf, status and RFID tag, as of today.' },
    { label: 'RFID tagging report', path: '/api/reports/export/tagging', file: 'tagging', uses: [...WHAT, 'itemStatus'],
      about: 'Copies in shelf order and whether each has an RFID tag yet (withdrawn copies left out).' },
  ] },
  { id: 'fines', label: 'Fines & payments', reports: [
    { label: 'Fines & payments', path: '/api/reports/export/fines', file: 'fines', uses: ['period', ...WHO, ...WHAT, 'fineStatus'],
      about: 'Fines charged in the period, with payment or waiver. Material type and section match the borrowed copy.' },
  ] },
  { id: 'patrons', label: 'Patrons & attendance', reports: [
    { label: 'Patron list', path: '/api/reports/export/patrons', file: 'patrons', uses: WHO,
      about: 'Registered patrons as of today (the period does not apply).' },
    { label: 'Attendance in period', path: '/api/attendance/export', file: 'attendance', uses: ['period', ...WHO],
      about: 'Every library visit (time in / time out) in the period.' },
  ] },
  { id: 'gate', label: 'Security gate', reports: [
    { label: 'Gate events in period', path: '/api/gate/export', file: 'gate', uses: ['period'],
      about: 'Alarms, visitor counts, tamper and power events from the security gates.' },
  ] },
  { id: 'ebooks', label: 'E-book usage', reports: [
    { label: 'E-book usage in period', path: '/api/reports/export/ebooks', file: 'ebooks', uses: ['period', ...WHO],
      about: 'School-owned e-books opened in the period, by how many readers.' },
  ] },
]

const NO_FILTERS: Filters = { categoryId: '', courseId: '', materialType: '', section: '', itemStatus: '', fineStatus: '' }

export function ReportsPage() {
  const [tab, setTab] = useState<Tab>('circulation')
  const [from, setFrom] = useState(daysAgo(29))
  const [to, setTo] = useState(isoDate())
  const [flt, setFlt] = useState<Filters>(NO_FILTERS)
  const canMigrate = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const setF = (k: keyof Filters) => (e: { target: { value: string } }) => setFlt((x) => ({ ...x, [k]: e.target.value }))
  const { data: categories } = useAsync(() => api<Category[]>('/api/patron-categories'), [])
  const { data: courses } = useAsync(() => api<Course[]>('/api/courses'), [])
  const { data: sections } = useAsync(() => api<LibrarySection[]>('/api/sections'), [])

  const current = TABS.find((t) => t.id === tab)
  const reports = current?.reports ?? []
  const shown = new Set(reports.flatMap((r) => r.uses))
  /** The query for one report: the period and only the filters it uses, so nothing is silently ignored. */
  const paramsFor = (uses: FilterKey[]) => Object.fromEntries([
    ...(uses.includes('period') ? [['from', from], ['to', to]] : []),
    ...(Object.keys(flt) as (keyof Filters)[]).filter((k) => uses.includes(k) && flt[k]).map((k) => [k, flt[k]]),
  ]) as Record<string, string>
  /** "Not used by: Overdue items" when a filter on this tab doesn't apply to every report on it. */
  const hint = (k: FilterKey) => {
    const skip = reports.filter((r) => !r.uses.includes(k)).map((r) => r.label)
    return skip.length ? `Not used by: ${skip.join(', ')}` : undefined
  }
  const anyFilter = (Object.keys(flt) as (keyof Filters)[]).some((k) => shown.has(k) && flt[k])

  return (
    <>
      <div className="page-head"><h1>Reports & statistics</h1></div>
      <Tabs value={tab} onChange={setTab} tabs={[
        ...TABS.map((t) => ({ id: t.id, label: t.label })),
        ...(canMigrate ? [{ id: 'migration' as const, label: 'Data migration' }] : []),
      ]} />

      {shown.size > 0 && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2 style={{ margin: 0 }}>Filters</h2>
            {anyFilter && <button className="btn small" onClick={() => setFlt(NO_FILTERS)}>Clear filters</button>}
          </div>
          <p className="muted small" style={{ marginTop: 4 }}>Only the filters these reports use are shown. The figures, previews and downloads
            below follow them.</p>
          <div className="form-grid">
            {shown.has('period') && <>
              <Field label="From" hint={hint('period')}><input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
              <Field label="To"><input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
            </>}
            {shown.has('categoryId') && <Field label="Patron category" hint={hint('categoryId')}><select value={flt.categoryId} onChange={setF('categoryId')}>
              <option value="">All categories</option>{categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>}
            {shown.has('courseId') && <Field label="Course / program" hint={hint('courseId')}><select value={flt.courseId} onChange={setF('courseId')}>
              <option value="">All programs</option>{courses?.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></Field>}
            {shown.has('materialType') && <Field label="Material type" hint={hint('materialType')}><select value={flt.materialType} onChange={setF('materialType')}>
              <option value="">All types</option>{MATERIAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>}
            {shown.has('section') && <Field label="Section" hint={hint('section')}><select value={flt.section} onChange={setF('section')}>
              <option value="">All sections</option>{sections?.map((x) => <option key={x.id} value={x.name}>{x.name}</option>)}</select></Field>}
            {shown.has('itemStatus') && <Field label="Copy status" hint={hint('itemStatus')}><select value={flt.itemStatus} onChange={setF('itemStatus')}>
              <option value="">All statuses</option>{ITEM_STATUSES.map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>}
            {shown.has('fineStatus') && <Field label="Fine status" hint={hint('fineStatus')}><select value={flt.fineStatus} onChange={setF('fineStatus')}>
              <option value="">Paid, unpaid and waived</option>{(['UNPAID', 'PAID', 'WAIVED'] as const).map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>}
          </div>
        </div>
      )}

      {reports.map((r) => <ReportCard key={r.file} report={r} params={paramsFor(r.uses)} />)}
      {tab === 'circulation' && <CirculationCharts params={paramsFor(['period', ...WHO, ...WHAT])} from={from} to={to} />}
      {tab === 'ebooks' && <EbookUsageSection params={paramsFor(['period', ...WHO])} from={from} to={to} />}
      {tab === 'migration' && <MigrationCard />}
    </>
  )
}

/** One report: what it lists, the server's own summary (period, counts, filters), its first rows, and downloads. */
function ReportCard({ report, params }: { report: Report; params: Record<string, string> }) {
  const query = qs(params)
  const { data, error } = useAsync(() => api<Preview>(`${report.path}/preview${query}`), [report.path, query])
  const get = (f: Format) => download(withFormat(`${report.path}${query}`, f), `${report.file}.${f}`)
  return (
    <div className="card stack" style={{ marginBottom: 16 }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <h2 style={{ margin: 0 }}>{report.label}</h2>
          <p className="muted small" style={{ margin: '2px 0 0' }}>{report.about}</p>
        </div>
        <div className="row">
          <button className="btn small" onClick={() => get('xlsx')}><Icon name="download" />Excel</button>
          <button className="btn small" onClick={() => get('csv')}><Icon name="download" />CSV</button>
          <button className="btn small" onClick={() => get('pdf')}><Icon name="download" />PDF</button>
        </div>
      </div>
      <ErrorAlert error={error} />
      {data && <div className="alert small" style={{ margin: 0 }}><strong>{data.subtitle}</strong></div>}
      <div style={{ maxHeight: 420, overflow: 'auto' }}>
        <Table rows={data?.rows.map((cells, i) => ({ i, cells }))} rowKey={(r) => r.i} empty="No rows match these filters"
          columns={(data?.header ?? []).map((h, c) => ({ header: h, render: (r: { cells: string[] }) => r.cells[c] }))} />
      </div>
      {data && data.total > data.rows.length && (
        <span className="muted small">Showing the first {data.rows.length} of {data.total.toLocaleString()} rows. Download for all of them.</span>
      )}
    </div>
  )
}

interface Preview { title: string; subtitle: string; header: string[]; rows: string[][]; total: number }

function CirculationCharts({ params, from, to }: { params: Record<string, string>; from: string; to: string }) {
  const { data, error } = useAsync(() => api<CirculationStats>(`/api/reports/circulation${qs(params)}`), [qs(params)])
  if (error) return <ErrorAlert error={error} />
  if (!data) return null
  return (
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
  )
}

/** Data migration validation report: a signed document, so downloads only. */
function MigrationCard() {
  const get = (f: Format) => download(withFormat('/api/reports/migration', f), `migration-validation.${f}`)
  return (
    <div className="card stack">
      <h2 style={{ margin: 0 }}>Data migration validation report</h2>
      <p className="muted small" style={{ margin: 0 }}>Every import of the library's existing records, the rows rejected and why, the totals now in
        the system, the data privacy note, and sign-off lines for the supplier and the Learning Resource Center. Print the PDF for signing.</p>
      <div className="row">
        <button className="btn primary" onClick={() => get('pdf')}><Icon name="file-text" />PDF for signing</button>
        <button className="btn" onClick={() => get('xlsx')}><Icon name="download" />Excel</button>
        <button className="btn" onClick={() => get('csv')}><Icon name="download" />CSV</button>
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
function EbookUsageSection({ params, from, to }: { params: Record<string, string>; from: string; to: string }) {
  const { data, error } = useAsync(() => api<EbookUsage>(`/api/reports/ebooks${qs(params)}`), [qs(params)])
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
