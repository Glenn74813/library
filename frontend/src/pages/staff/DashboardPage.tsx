import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import type { AttendanceStats, Dashboard } from '../../api/types'
import { BarList, ColumnChart, ErrorAlert, Stat } from '../../components/ui'
import { fmtMoney } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

export function DashboardPage() {
  const { data: d, error } = useAsync(() => api<Dashboard>('/api/reports/dashboard'), [])
  const { data: att } = useAsync(() => api<AttendanceStats>('/api/attendance/stats'), [])

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
            <h2>Visits, last 30 days</h2>
            <ColumnChart data={att.daily} format={(l) => l.slice(8)} />
          </div>
          <div className="card">
            <h2>Top 5 programs / courses</h2>
            <BarList data={att.topCourses} />
          </div>
        </div>
      )}
    </>
  )
}
