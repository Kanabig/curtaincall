export type PerformanceState = 'UPCOMING' | 'ONGOING' | 'ENDED'
export type SaleState = 'SCHEDULED' | 'ON_SALE' | 'SUSPENDED' | 'CLOSED'

export interface PerformanceItem {
  id: number
  name: string
  genre: string | null
  area: string | null
  state: PerformanceState
  startDate: string
  endDate: string
  facilityName: string | null
  poster: string | null
  minPrice: number | null
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
}

export interface PriceItem {
  grade: string
  price: number
}

export interface SessionItem {
  id: number
  startAt: string
  saleOpenAt: string
  saleState: SaleState
  prices: PriceItem[]
}

export interface PerformanceDetail {
  id: number
  name: string
  genre: string | null
  area: string | null
  state: PerformanceState
  startDate: string
  endDate: string
  openRun: boolean
  facilityName: string | null
  poster: string | null
  runtime: string | null
  age: string | null
  priceGuidance: string | null
  dateGuidance: string | null
  sessions: SessionItem[]
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export interface UserInfo {
  id: number
  email: string
  name: string
  role: 'USER' | 'ADMIN'
}

export interface PerformanceFilter {
  keyword?: string
  region?: string
  genre?: string
  from?: string
  to?: string
  state?: PerformanceState | ''
  maxPrice?: string
  page?: number
}

export interface SessionBody {
  startAt: string
  saleOpenAt: string
  prices: PriceItem[]
  admissionPerSecond: number
  maxActiveUsers: number
}

export interface AdminSession extends SessionBody {
  sessionId: number
  performanceId: number
  venueId: string
}

export type QueueEntry =
  | { status: 'WAITING'; position: number; ahead: number; etaSeconds: number; pollAfterMs: number }
  | { status: 'SOLD_OUT'; position: number; ahead: number; pollAfterMs: number }
  | { status: 'ADMITTED'; entryToken: string; expiresAt: string }
  | { status: 'EXPIRED' }

export interface SeatInfo {
  seatId: number
  floor: number
  section: string
  row: string
  number: number
  x: number
  y: number
  grade: string
  price: number
  wheelchair: boolean
}

export interface SeatStatus {
  version: number
  held: number[]
  sold: number[]
}

export interface Hold {
  holdId: string
  seatIds: number[]
  expiresAt: string
}
