export type Role = 'ADMIN' | 'LIBRARIAN' | 'STAFF' | 'KIOSK' | 'GATE'

export interface Principal {
  subject: string
  username: string
  displayName: string
  roles: string[]
  patronId: number | null
}

export interface AuthResponse {
  accessToken: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  user: Principal
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface User {
  id: number
  username: string
  fullName: string
  role: Role
  enabled: boolean
  createdAt: string
}

export interface Category {
  id: number
  name: string
  loanDays: number
  maxLoans: number
  maxRenewals: number
  finePerDay: number
  maxUnpaidFines: number
}

export interface Course {
  id: number
  code: string
  name: string
}

export interface Patron {
  id: number
  cardNumber: string
  rfidUid: string | null
  firstName: string
  lastName: string
  fullName: string
  email: string | null
  phone: string | null
  categoryId: number
  categoryName: string
  courseId: number | null
  courseCode: string | null
  photoUrl: string | null
  expiryDate: string | null
  blocked: boolean
  blockReason: string | null
  hasPin: boolean
  createdAt: string
}

export const MATERIAL_TYPES = ['BOOK', 'AUDIOVISUAL', 'SERIAL', 'THESIS', 'EBOOK', 'REFERENCE', 'OTHER'] as const
export type MaterialType = (typeof MATERIAL_TYPES)[number]

export const ITEM_STATUSES = ['AVAILABLE', 'ON_LOAN', 'ON_HOLD', 'LOST', 'MISSING', 'DAMAGED', 'WITHDRAWN'] as const
export type ItemStatus = (typeof ITEM_STATUSES)[number]

export interface BibRecord {
  id: number
  title: string
  subtitle: string | null
  authors: string | null
  isbn: string | null
  publisher: string | null
  publicationYear: number | null
  edition: string | null
  subjects: string | null
  callNumber: string | null
  materialType: MaterialType
  language: string | null
  summary: string | null
  totalCopies: number
  availableCopies: number
  createdAt: string
  updatedAt: string
  ebook: DigitalSummary | null
}

/** School-owned e-books are uploaded and readable in the app; commercial ones only link to the vendor. */
export type EbookKind = 'SCHOOL_OWNED' | 'COMMERCIAL'
export type EbookFormat = 'PDF' | 'EPUB'

export interface DigitalSummary {
  kind: EbookKind
  format: EbookFormat | null
  provider: string | null
  externalUrl: string | null
}

export interface DigitalInfo {
  kind: EbookKind | null
  filename: string | null
  format: EbookFormat | null
  sizeBytes: number | null
  uploadedAt: string | null
  uploadedBy: string | null
  externalUrl: string | null
  externalProvider: string | null
  views: number
}

export interface Ebook {
  recordId: number
  title: string
  authors: string | null
  publicationYear: number | null
  materialType: MaterialType
  kind: EbookKind
  format: EbookFormat | null
  sizeBytes: number | null
  provider: string | null
  externalUrl: string | null
}

export interface Item {
  id: number
  recordId: number
  title: string
  callNumber: string | null
  barcode: string
  rfidEpc: string | null
  location: string | null
  status: ItemStatus
  acquiredDate: string | null
  price: number | null
  notes: string | null
  dueAt: string | null
}

export interface RecordDetail {
  record: BibRecord
  items: Item[]
}

export interface OpacItem {
  barcode: string
  location: string | null
  callNumber: string | null
  status: ItemStatus
  dueAt: string | null
}

export interface OpacDetail {
  record: BibRecord
  items: OpacItem[]
}

export interface IsbnResult {
  isbn: string
  title: string | null
  subtitle: string | null
  authors: string | null
  publisher: string | null
  publicationYear: number | null
  subjects: string | null
  coverUrl: string | null
}

export interface Loan {
  id: number
  itemId: number
  barcode: string
  recordId: number
  title: string
  callNumber: string | null
  patronId: number
  patronName: string
  cardNumber: string
  checkoutAt: string
  dueAt: string
  returnedAt: string | null
  renewals: number
  maxRenewals: number
  overdue: boolean
  overdueDays: number
  accruedFine: number
}

export type HoldStatus = 'PENDING' | 'READY' | 'FULFILLED' | 'CANCELLED' | 'EXPIRED'

export interface Hold {
  id: number
  recordId: number
  title: string
  patronId: number
  patronName: string
  cardNumber: string
  status: HoldStatus
  placedAt: string
  readyAt: string | null
  expiresAt: string | null
  itemBarcode: string | null
  queuePosition: number | null
}

export type FineStatus = 'UNPAID' | 'PAID' | 'WAIVED'

export interface Fine {
  id: number
  patronId: number
  patronName: string
  cardNumber: string
  amount: number
  reason: string
  status: FineStatus
  createdAt: string
  settledAt: string | null
  receiptNumber: string | null
  settledBy: string | null
}

export interface Receipt {
  receiptNumber: string
  patronName: string
  cardNumber: string
  fines: Fine[]
  total: number
  paidAt: string
  cashier: string
}

export interface PatronSummary {
  patron: Patron
  loans: Loan[]
  holds: Hold[]
  unpaidFines: Fine[]
  unpaidTotal: number
  maxLoans: number
  blocks: string[]
}

export interface CheckoutResult {
  loan: Loan
  warnings: string[]
}

export interface CheckinResult {
  loan: Loan | null
  fineAmount: number
  holdForPatron: string | null
  message: string
}

export interface KioskConfig {
  mode: 'ATTENDANCE' | 'STANDBY'
  singleTap: boolean
  entryMessage: string
  exitMessage: string
  standbyMessages: string[]
  videoUrl: string
  broadcast: { message: string; at: string } | null
}

export type EntryMethod = 'RFID' | 'BARCODE' | 'MANUAL'

export interface TapResult {
  action: 'IN' | 'OUT'
  patronName: string
  cardNumber: string
  course: string | null
  category: string
  photoUrl: string | null
  message: string
  time: string
  warnings: string[]
}

export interface AttendanceLog {
  id: number
  patronId: number
  patronName: string
  cardNumber: string
  course: string | null
  category: string
  timeIn: string
  timeOut: string | null
  method: EntryMethod
}

export interface Count {
  label: string
  count: number
}

export interface AttendanceStats {
  totalVisits: number
  uniquePatrons: number
  currentlyInside: number
  topCourses: Count[]
  topCategories: Count[]
  daily: Count[]
  hourly: Count[]
}

export type GateEventType =
  | 'ALARM' | 'TAMPER' | 'POWER_LOSS' | 'POWER_RESTORED' | 'VISITOR'
  | 'EMERGENCY_ON' | 'EMERGENCY_OFF' | 'HEARTBEAT' | 'BROADCAST'

export interface GateEvent {
  id: number
  gateId: string
  type: GateEventType
  epc: string | null
  barcode: string | null
  title: string | null
  visitorCount: number | null
  message: string | null
  occurredAt: string
}

export interface GateSummary {
  alarmsToday: number
  visitorsToday: number
  tamperToday: number
  powerLossToday: number
  emergencyMode: boolean
  alarmEnabled: boolean
  gates: { gateId: string; lastSeen: string; online: boolean }[]
}

export interface StocktakeSession {
  id: number
  name: string
  location: string | null
  startedBy: string
  startedAt: string
  closedAt: string | null
  scanned: number
}

export interface ScanResult {
  code: string
  result: 'OK' | 'CHECK' | 'MISPLACED' | 'UNKNOWN' | 'DUPLICATE'
  barcode: string | null
  title: string | null
  status: ItemStatus | null
  location: string | null
  message: string
}

export interface ReportItem {
  barcode: string
  title: string
  callNumber: string | null
  location: string | null
  status: ItemStatus
}

export interface StocktakeReport {
  session: StocktakeSession
  expected: number
  found: number
  missing: ReportItem[]
  misplaced: ReportItem[]
  unexpectedStatus: ReportItem[]
  unknownCodes: string[]
}

export interface Dashboard {
  records: number
  items: number
  patrons: number
  activeLoans: number
  overdueLoans: number
  holdsPending: number
  holdsReady: number
  unpaidFines: number
  checkoutsToday: number
  returnsToday: number
  visitsToday: number
  insideNow: number
  finesCollectedToday: number
  itemsMissing: number
  itemsLost: number
}

export interface Ranked {
  id: number
  code: string | null
  label: string
  count: number
}

export interface CirculationStats {
  from: string
  to: string
  checkouts: number
  returns: number
  finesCollected: number
  topBorrowers: Ranked[]
  topTitles: Ranked[]
}

export interface AuditEntry {
  id: number
  actor: string
  action: string
  details: string | null
  createdAt: string
}

export type TaskSeverity = 'DANGER' | 'WARNING' | 'INFO'

/** Actionable task shown in the staff notification bell (computed live by the server). */
export interface Task {
  id: string
  severity: TaskSeverity
  title: string
  detail: string
  count: number
  link: string
}
