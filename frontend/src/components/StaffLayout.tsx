import { useEffect, useState } from 'react'
import { NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { logout } from '../api/client'
import { hasRole, STAFF_ROLES, useSession } from '../auth/AuthContext'
import { Icon, type IconName } from './Icon'
import { Logo } from './Logo'
import { NotificationBell } from './NotificationBell'
import { useLibrary } from '../api/library'

interface NavItem { to: string; label: string; roles?: string[] }

/** Module groups; only the group (parent module) carries an icon, its pages are plain links. */
const NAV: { section: string; icon: IconName; items: NavItem[] }[] = [
  { section: 'Overview', icon: 'bar-chart-2', items: [{ to: '/staff', label: 'Dashboard' }] },
  {
    section: 'Circulation',
    icon: 'repeat',
    items: [
      { to: '/staff/circulation', label: 'Check out / in' },
      { to: '/staff/loans', label: 'Loans & overdue' },
      { to: '/staff/renewals', label: 'Renewal requests' },
      { to: '/staff/holds', label: 'Holds' },
      { to: '/staff/fines', label: 'Fines & payments' },
    ],
  },
  {
    section: 'Collection',
    icon: 'book',
    items: [
      { to: '/staff/catalog', label: 'Catalog' },
      { to: '/staff/inventory', label: 'Stocktake' },
      { to: '/staff/authorities', label: 'Authorities' },
      { to: '/staff/labels', label: 'Labels & library IDs' },
    ],
  },
  {
    section: 'People',
    icon: 'users',
    items: [
      { to: '/staff/patrons', label: 'Patrons' },
      { to: '/staff/attendance', label: 'Attendance' },
    ],
  },
  {
    section: 'Security & reports',
    icon: 'shield',
    items: [
      { to: '/staff/gate', label: 'Security gate' },
      { to: '/staff/reports', label: 'Reports' },
      { to: '/staff/print-log', label: 'Print log' },
    ],
  },
  {
    section: 'Administration',
    icon: 'settings',
    items: [
      { to: '/staff/settings', label: 'Loan rules & courses' },
      { to: '/staff/calendar', label: 'Library calendar' },
      { to: '/staff/catalog-fields', label: 'Catalog fields', roles: ['ADMIN', 'LIBRARIAN'] },
      { to: '/staff/notifications', label: 'Notifications', roles: ['ADMIN', 'LIBRARIAN'] },
      { to: '/staff/users', label: 'Users & devices', roles: ['ADMIN'] },
      { to: '/staff/audit', label: 'Audit trail', roles: ['ADMIN'] },
      { to: '/staff/privacy', label: 'Data privacy', roles: ['ADMIN'] },
      { to: '/staff/system', label: 'System settings', roles: ['ADMIN'] },
    ],
  },
]

const COLLAPSED_KEY = 'smartlib.nav.collapsed'
const PUBLIC_SECTION = 'Public screens'

function loadCollapsed(): string[] {
  try { return JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? '[]') } catch { return [] }
}

export function StaffLayout() {
  const session = useSession()
  const lib = useLibrary()
  const [open, setOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  // Sidebar sections the user folded away; remembered on this computer.
  const [collapsed, setCollapsed] = useState<string[]>(loadCollapsed)

  useEffect(() => setOpen(false), [location.pathname])

  // Updates from the latest state, so quick successive clicks are all kept.
  const updateCollapsed = (change: (prev: string[]) => string[]) => setCollapsed((prev) => {
    const next = change(prev)
    try { localStorage.setItem(COLLAPSED_KEY, JSON.stringify(next)) } catch { /* storage unavailable */ }
    return next
  })
  const toggle = (section: string) =>
    updateCollapsed((prev) => (prev.includes(section) ? prev.filter((s) => s !== section) : [...prev, section]))

  // Opening a page (from a link or the bell) unfolds its section, so the current page is always visible.
  useEffect(() => {
    const current = NAV.find((g) => g.items.some((i) => i.to === '/staff' ? location.pathname === '/staff' : location.pathname.startsWith(i.to)))
    if (current) updateCollapsed((prev) => (prev.includes(current.section) ? prev.filter((s) => s !== current.section) : prev))
  }, [location.pathname])

  const sectionHead = (name: string, icon: IconName) => {
    const isOpen = !collapsed.includes(name)
    return (
      <button type="button" className={`section ${isOpen ? '' : 'collapsed'}`} aria-expanded={isOpen} onClick={() => toggle(name)}>
        <Icon name={icon} />{name}<Icon name="chevron-down" className="chevron" />
      </button>
    )
  }

  if (!hasRole(session, ...STAFF_ROLES)) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (session?.user.mustChangePassword && location.pathname !== '/staff/account') {
    return <Navigate to="/staff/account" replace />
  }

  return (
    <div className="shell">
      {open && <div className="scrim" onClick={() => setOpen(false)} />}
      <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="Main navigation">
        <div className="brand-block">
          <Logo size={48} />
          <div>
            <strong>Smart Library</strong>
            <small>{lib.name}</small>
          </div>
        </div>
        <nav className="nav">
          {NAV.map((group) => {
            const items = group.items.filter((i) => !i.roles || hasRole(session, ...i.roles))
            if (!items.length) return null
            return (
              <div key={group.section} style={{ display: 'contents' }}>
                {sectionHead(group.section, group.icon)}
                {!collapsed.includes(group.section) && items.map((i) => (
                  <NavLink key={i.to} to={i.to} end={i.to === '/staff'}>{i.label}</NavLink>
                ))}
              </div>
            )
          })}
          {sectionHead(PUBLIC_SECTION, 'monitor')}
          {!collapsed.includes(PUBLIC_SECTION) && <>
            <NavLink to="/opac" target="_blank">OPAC kiosk</NavLink>
            <NavLink to="/kiosk/attendance" target="_blank">Attendance kiosk</NavLink>
            <NavLink to="/kiosk/selfcheck" target="_blank">Self-checkout kiosk</NavLink>
          </>}
        </nav>
        <div className="user-block">
          <div><strong>{session!.user.displayName}</strong></div>
          <div className="muted small">{session!.user.username} · {session!.user.roles.join(', ')}</div>
          <div className="row" style={{ marginTop: 8 }}>
            <NavLink className="btn small" to="/staff/account">Account</NavLink>
            <button className="btn small" onClick={async () => { await logout(); navigate('/login') }}><Icon name="log-out" />Sign out</button>
          </div>
        </div>
      </aside>
      <div className="content">
        <header className="topbar">
          <button className="icon-btn menu-btn" aria-label="Open menu" onClick={() => setOpen(true)}><Icon name="menu" /></button>
          <Logo /><span className="brand">{lib.systemName}</span>
          <NotificationBell />
        </header>
        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
