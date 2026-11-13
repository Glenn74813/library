import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { StaffLayout } from './components/StaffLayout'
import { StaffLogin } from './pages/staff/StaffLogin'
import { DashboardPage } from './pages/staff/DashboardPage'
import { CirculationPage } from './pages/staff/CirculationPage'
import { LoansPage } from './pages/staff/LoansPage'
import { HoldsPage } from './pages/staff/HoldsPage'
import { FinesPage } from './pages/staff/FinesPage'
import { CatalogPage } from './pages/staff/CatalogPage'
import { RecordPage } from './pages/staff/RecordPage'
import { PatronsPage } from './pages/staff/PatronsPage'
import { PatronDetailPage } from './pages/staff/PatronDetailPage'
import { AttendancePage } from './pages/staff/AttendancePage'
import { GatePage } from './pages/staff/GatePage'
import { InventoryPage } from './pages/staff/InventoryPage'
import { ReportsPage } from './pages/staff/ReportsPage'
import { SettingsPage } from './pages/staff/SettingsPage'
import { CalendarPage } from './pages/staff/CalendarPage'
import { AuthoritiesPage } from './pages/staff/AuthoritiesPage'
import { CatalogFieldsPage } from './pages/staff/CatalogFieldsPage'
import { SectionsPage } from './pages/staff/SectionsPage'
import { NotificationsPage } from './pages/staff/NotificationsPage'
import { UsersPage } from './pages/staff/UsersPage'
import { AuditPage } from './pages/staff/AuditPage'
import { PrintLogPage } from './pages/staff/PrintLogPage'
import { PrivacyPage } from './pages/staff/PrivacyPage'
import { SystemSettingsPage } from './pages/staff/SystemSettingsPage'
import { LabelsPage } from './pages/staff/LabelsPage'
import { RenewalsPage } from './pages/staff/RenewalsPage'
import { PrivacyNotice } from './pages/public/PrivacyNotice'
import { AccountPage } from './pages/staff/AccountPage'
import { OpacKiosk } from './pages/opac/OpacKiosk'
import { AttendanceKiosk } from './pages/kiosk/AttendanceKiosk'
import { SelfCheckKiosk } from './pages/kiosk/SelfCheckKiosk'
import { PatronLogin } from './pages/portal/PatronLogin'
import { ForgotPin } from './pages/portal/ForgotPin'
import { PatronPortal } from './pages/portal/PatronPortal'

// The reader pulls in PDF.js and epub.js, so it is only downloaded when a book is opened.
const EbookReader = lazy(() => import('./pages/reader/EbookReader').then((m) => ({ default: m.EbookReader })))

export default function App() {
  return (
    <Routes>
      {/* The root opens the staff app (its sign-in page when nobody is signed in); patrons use /my and /opac. */}
      <Route path="/" element={<Navigate to="/staff" replace />} />
      <Route path="/login" element={<StaffLogin />} />
      <Route path="/staff" element={<StaffLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="circulation" element={<CirculationPage />} />
        <Route path="loans" element={<LoansPage />} />
        <Route path="holds" element={<HoldsPage />} />
        <Route path="fines" element={<FinesPage />} />
        <Route path="catalog" element={<CatalogPage />} />
        <Route path="catalog/new" element={<RecordPage />} />
        <Route path="catalog/:id" element={<RecordPage />} />
        <Route path="catalog/:id/edit" element={<RecordPage edit />} />
        <Route path="patrons" element={<PatronsPage />} />
        <Route path="patrons/:id" element={<PatronDetailPage />} />
        <Route path="attendance" element={<AttendancePage />} />
        <Route path="gate" element={<GatePage />} />
        <Route path="inventory" element={<InventoryPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="calendar" element={<CalendarPage />} />
        <Route path="authorities" element={<AuthoritiesPage />} />
        <Route path="catalog-fields" element={<CatalogFieldsPage />} />
        <Route path="sections" element={<SectionsPage />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="print-log" element={<PrintLogPage />} />
        <Route path="privacy" element={<PrivacyPage />} />
        <Route path="system" element={<SystemSettingsPage />} />
        <Route path="labels" element={<LabelsPage />} />
        <Route path="renewals" element={<RenewalsPage />} />
        <Route path="account" element={<AccountPage />} />
      </Route>
      <Route path="/privacy" element={<PrivacyNotice />} />
      <Route path="/opac" element={<OpacKiosk />} />
      <Route path="/kiosk/attendance" element={<AttendanceKiosk />} />
      <Route path="/kiosk/selfcheck" element={<SelfCheckKiosk />} />
      <Route path="/my/login" element={<PatronLogin />} />
      <Route path="/my/forgot-pin" element={<ForgotPin />} />
      <Route path="/my" element={<PatronPortal />} />
      <Route path="/my/catalog" element={<OpacKiosk student />} />
      <Route path="/read/:recordId" element={<Suspense fallback={<div className="center-page muted">Opening reader…</div>}><EbookReader /></Suspense>} />
      <Route path="*" element={<Navigate to="/staff" replace />} />
    </Routes>
  )
}
