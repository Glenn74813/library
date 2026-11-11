import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import type { AttendanceStats, Dashboard } from '../../api/types'
import { ErrorAlert, Stat } from '../../components/ui'
import { ColumnChart, PieChart, dayLabel, dayTick, fillDays } from '../../components/charts'
import { daysAgo, fmtMoney, isoDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

export function DashboardPage() {
  const { data: d, error } = useAsync(() => api<Dashboard>('/api/reports/dashboard'), [])
  const { data: att } = useAsync(() => api<AttendanceStats>('/api/attendance/stats'), [])
  const visits = att ? fillDays(att.daily, daysAgo(29), isoDate()) : []

  return (
    <>
      <div className="page-head">
        <h1>Dashboard</h1>
        <div className="row">
          <Link className="btn primary" to="/staff/circulation"><Icon name="repeat" />Circulation desk</Link>
          <Link className="btn" to="/staff/catalog/new"><Icon name="plus" />New record</Link>
        </div>
      </div>
      <ErrorAlert error={error} />
      {d && (
        <div className="stack">
          <h2>Today</h2>
          <div className="stats">
            <Stat label="Check-outs" value={d.checkoutsToday} />
            <Stat label="Returns" value={d.returnsToday} />
            <Stat label="Library visits" value={d.visitsToday} />
            <Stat label="Inside now" value={d.insideNow} />
            <Stat label="Fines collected" value={fmtMoney(d.finesCollectedToday)} />
          </div>
          <h2>Circulation</h2>
          <div className="stats">
            <Stat label="On loan" value={d.activeLoans} />
            <Stat label="Overdue" value={d.overdueLoans} tone={d.overdueLoans ? 'bad' : undefined} />
            <Stat label="Holds waiting" value={d.holdsPending} />
            <Stat label="Holds ready" value={d.holdsReady} tone={d.holdsReady ? 'warn' : undefined} />
            <Stat label="Unpaid fines" value={fmtMoney(d.unpaidFines)} />
          </div>
          <h2>Collection</h2>
          <div className="stats">
            <Stat label="Titles" value={d.records} />
            <Stat label="Copies" value={d.items} />
            <Stat label="Patrons" value={d.patrons} />
            <Stat label="Missing" value={d.itemsMissing} tone={d.itemsMissing ? 'warn' : undefined} />
            <Stat label="Lost" value={d.itemsLost} tone={d.itemsLost ? 'warn' : undefined} />
          </div>
        </div>
      )}
      {att && (
        <div className="grid cols-2" style={{ marginTop: 16 }}>
          <div className="card">
            <ColumnChart title="Library visits" subtitle="Attendance · last 30 days" data={visits}
              format={dayTick} tooltipLabel={dayLabel} total={att.totalVisits.toLocaleString('en-PH')}
              totalNote={`Today: ${visits[visits.length - 1]?.count ?? 0}`} unit=" visit(s)" />
          </div>
          <div className="card">
            <PieChart title="Top 5 programs / courses" subtitle="Library visits · last 30 days" data={att.topCourses} unit=" visit(s)" />
          </div>
        </div>
      )}
    </>
  )
}
