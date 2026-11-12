export type Role = 'ADMIN' | 'LIBRARIAN' | 'STAFF' | 'KIOSK' | 'GATE'

export interface Principal {
  subject: string
  username: string
  displayName: string
  roles: string[]
  patronId: number | null
  /** Signed in with a temporary password set by an administrator: must choose a new one first. */
  mustChangePassword?: boolean
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
  /** Set while the account is locked after repeated failed sign-ins. */
  lockedUntil: string | null
  /** Kiosks and gates: whether a device key is issued, when, and when the device last connected. */
  hasDeviceKey?: boolean
  deviceKeyIssuedAt?: string | null
  deviceLastSeenAt?: string | null
}

export interface Category {
  id: number
  name: string
  loanDays: number
  maxLoans: number
  maxRenewals: number
  finePerDay: number
  maxUnpaidFines: number
  /** Days a reserved copy waits on the hold shelf for pickup. */
  holdPickupDays: number
  /** Active reservations + bookings allowed; 0 = no limit. */
  maxHolds: number
  /** Late open days not charged. */
  fineGraceDays: number
  /** Overdue fine cap per item; 0 = no cap. */
  maxFinePerItem: number
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
  anonymizedAt: string | null
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
  /** Values of library-defined custom fields, by field key. */
  customFields: Record<string, string>
}

export interface CustomFieldDef {
  id: number
  key: string
  label: string
  type: 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT'
  options: string[]
  showInOpac: boolean
  sortOrder: number
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
  /** Section / area, e.g. "Fiction Section". */
  location: string | null
  aisle: string | null
  shelf: string | null
  /** "Fiction Section · Aisle 3 · Shelf 2" */
  shelfLocation: string | null
  /** Effective rule for this copy (material type rule unless overridden). */
  roomUseOnly: boolean
  /** null = follow the material type; true/false = set on this copy */
  roomUseOverride: boolean | null
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
  aisle: string | null
  shelf: string | null
  shelfLocation: string | null
  callNumber: string | null
  status: ItemStatus
  dueAt: string | null
  roomUseOnly: boolean
}

export interface OpacDetail {
  record: BibRecord
  items: OpacItem[]
  /** Custom fields marked "show in OPAC", with labels. */
  extraFields: { label: string; value: string }[]
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
  /** Staff member who checked the item out (released it to the patron). */
  checkedOutBy: string | null
}

export type HoldStatus = 'SCHEDULED' | 'PENDING' | 'READY' | 'FULFILLED' | 'CANCELLED' | 'EXPIRED'

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
  /** Where the set-aside copy is shelved (to pull it for the hold shelf). */
  itemShelfLocation: string | null
  queuePosition: number | null
  /** Advance booking: the date the patron wants to pick the book up. */
  pickupDate: string | null
  /** Booking that may not get a copy in time (none on the shelf or due back before the date). */
  atRisk: boolean
  /** When the reservation ended and who did it (staff username, patron card number, or "system ..."). */
  closedAt: string | null
  closedBy: string | null
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
  /** e.g. the loan was shortened because another patron booked the title */
  notice: string | null
}

export interface BookingCalendar {
  firstDate: string
  lastDate: string
  unavailable: string[]
  copies: number
}

export interface CheckinResult {
  loan: Loan | null
  fineAmount: number
  holdForPatron: string | null
  message: string
  /** Set when the copy was put aside for a reservation: details for the hold shelf slip. */
  holdSlip: { holdId: number; title: string; barcode: string; callNumber: string | null; cardNumber: string; initials: string; pickupBy: string | null } | null
}

export interface KioskConfig {
  mode: 'ATTENDANCE' | 'STANDBY'
  singleTap: boolean
  entryMessage: string
  exitMessage: string
  standbyMessages: string[]
  videoUrl: string
  /** Current announcement; `hasAudio` means kiosks play the preset's recording instead of speaking. */
  broadcast: { message: string; at: string; presetId: string | null; hasAudio: boolean } | null
  voice: VoiceSettings
}

/** Announcement text-to-speech settings (shared by every kiosk/display). */
export interface VoiceSettings {
  /** Browser voice name; used only where that voice is installed. Empty = best voice for `lang`. */
  name: string
  lang: string
  rate: number
  pitch: number
  repeat: number
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
  config: GateConfig
}

export interface GateConfig {
  emergencyMode: boolean
  alarmEnabled: boolean
  /** ALWAYS | OPEN_HOURS | CUSTOM */
  schedule: 'ALWAYS' | 'OPEN_HOURS' | 'CUSTOM'
  armFrom: string | null
  armTo: string | null
  armedNow: boolean
}

export interface StocktakeSession {
  id: number
  name: string
  location: string | null
  /** One aisle of that section; null = the whole section. */
  aisle: string | null
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

/** A copy that should be on the shelves being counted, in shelf order. */
export interface ShelfRow { aisle: string; shelf: string | null; callNumber: string | null; title: string; barcode: string; scanned: boolean }

/** Shelf list vs. scanned, for one aisle. */
export interface AisleCount { aisle: string; expected: number; found: number; missing: number }

export interface StocktakeReport {
  session: StocktakeSession
  expected: number
  found: number
  aisles: AisleCount[]
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

export type RenewalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

/** A patron's renewal request, decided by staff. */
export interface RenewalRequest {
  id: number
  loanId: number
  title: string
  barcode: string
  patronId: number
  patronName: string
  cardNumber: string
  status: RenewalStatus
  channel: 'PORTAL' | 'KIOSK' | 'SIP2'
  requestedAt: string
  dueAt: string
  /** Due date if approved now (pending requests only). */
  proposedDueAt: string | null
  newDueAt: string | null
  decidedAt: string | null
  decidedBy: string | null
  reason: string | null
  renewals: number
  maxRenewals: number
  /** Why approving would fail now (e.g. someone reserved the title since). */
  problem: string | null
}

/** An aisle of a section and its number of shelves (shelves are numbered 1 to `shelves`). */
export interface LibraryAisle { id: number; name: string; shelves: number; copies: number }

/** One of the library's sections / areas (Administration → Sections & Aisles), with its aisles. */
export interface LibrarySection { id: number; name: string; sortOrder: number; copies: number; aisles: LibraryAisle[] }
