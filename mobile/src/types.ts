// The parts of the library API's answers the app uses (same shapes as the web portal's api/types.ts).

export interface AuthResponse {
  accessToken: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  user: { displayName: string; roles: string[]; patronId: number | null }
}

export interface Page<T> { content: T[]; page: number; totalPages: number; totalElements: number }

export interface Patron {
  id: number
  cardNumber: string
  firstName: string
  lastName: string
  fullName: string
  categoryName: string
  courseCode: string | null
  photoUrl: string | null
  expiryDate: string | null
  blocked: boolean
}

export interface Loan {
  id: number
  title: string
  callNumber: string | null
  barcode: string
  checkoutAt: string
  dueAt: string
  renewals: number
  maxRenewals: number
  overdue: boolean
  overdueDays: number
  accruedFine: number | null
}

export type HoldStatus = 'SCHEDULED' | 'PENDING' | 'READY' | 'FULFILLED' | 'CANCELLED' | 'EXPIRED'

export interface Hold {
  id: number
  recordId: number
  title: string
  status: HoldStatus
  placedAt: string
  expiresAt: string | null
  queuePosition: number | null
  pickupDate: string | null
}

export interface Fine {
  id: number
  amount: number
  reason: string
  status: 'UNPAID' | 'PAID' | 'WAIVED'
  createdAt: string
  receiptNumber: string | null
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

export interface RenewalRequest {
  id: number
  loanId: number
  title: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  requestedAt: string
  dueAt: string
  newDueAt: string | null
  reason: string | null
}

export interface BibRecord {
  id: number
  title: string
  authors: string | null
  publicationYear: number | null
  callNumber: string | null
  materialType: string
  totalCopies: number
  availableCopies: number
}
