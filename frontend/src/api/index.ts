import { request, tokenStore } from './client'
import type {
  AdminSession,
  Hold,
  Order,
  OrderDetail,
  Page,
  PerformanceDetail,
  PerformanceFilter,
  PerformanceItem,
  QueueEntry,
  Reservation,
  ReservationDetail,
  ReservationPage,
  ReservationStatus,
  SeatInfo,
  SeatStatus,
  TokenResponse,
  SessionBody,
  UserInfo,
} from './types'

export function buildListQuery(f: PerformanceFilter, size = 20): string {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(f)) {
    if (k === 'page') continue
    if (typeof v === 'string' && v.trim() !== '') p.set(k, v.trim())
  }
  p.set('page', String(f.page ?? 0))
  p.set('size', String(size))
  return p.toString()
}

export const api = {
  performances: (f: PerformanceFilter) =>
    request<Page<PerformanceItem>>(`/performances?${buildListQuery(f)}`),
  performance: (id: string) => request<PerformanceDetail>(`/performances/${encodeURIComponent(id)}`),

  enterQueue: (sessionId: string) =>
    request<QueueEntry>(`/queue/sessions/${sessionId}/entries`, { method: 'POST', auth: true }),
  queueStatus: (sessionId: string) =>
    request<QueueEntry>(`/queue/sessions/${sessionId}/entries/me`, { auth: true }),
  leaveQueue: (sessionId: string) =>
    request<void>(`/queue/sessions/${sessionId}/entries/me`, { method: 'DELETE', auth: true }),

  // 배치는 회차가 판매되는 동안 바뀌지 않는다. ETag는 브라우저 HTTP 캐시가 처리한다
  seatMap: (sessionId: string) => request<SeatInfo[]>(`/sessions/${sessionId}/seat-map`),
  seatStatus: (sessionId: string) => request<SeatStatus>(`/sessions/${sessionId}/seat-status`, { auth: true }),
  hold: (sessionId: string, seatIds: number[], entryToken: string) =>
    request<Hold>(`/sessions/${sessionId}/holds`, {
      method: 'POST',
      body: { seatIds },
      auth: true,
      headers: { 'X-Entry-Token': entryToken },
    }),
  releaseHold: (holdId: string) => request<void>(`/holds/${holdId}`, { method: 'DELETE', auth: true }),

  createOrder: (holdId: string, entryToken: string, idempotencyKey: string) =>
    request<Order>('/orders', {
      method: 'POST',
      body: { holdId },
      auth: true,
      headers: { 'X-Entry-Token': entryToken, 'Idempotency-Key': idempotencyKey },
    }),
  startPayment: (orderId: string, cardToken: string, entryToken: string, idempotencyKey: string) =>
    request<{ status: string }>(`/orders/${orderId}/payment`, {
      method: 'POST',
      body: { method: 'CARD', cardToken },
      auth: true,
      headers: { 'X-Entry-Token': entryToken, 'Idempotency-Key': idempotencyKey },
    }),
  order: (orderId: string) => request<OrderDetail>(`/orders/${orderId}`, { auth: true }),
  reservations: (page: number, status?: ReservationStatus) => {
    const p = new URLSearchParams({ page: String(page), size: '20' })
    if (status) p.set('status', status)
    return request<ReservationPage>(`/reservations?${p}`, { auth: true })
  },
  reservation: (id: string) => request<ReservationDetail>(`/reservations/${id}`, { auth: true }),
  cancelReservation: (id: string, idempotencyKey: string) =>
    request<Pick<Reservation, 'reservationId' | 'status'>>(`/reservations/${id}/cancel`, {
      method: 'POST',
      auth: true,
      headers: { 'Idempotency-Key': idempotencyKey },
    }),

  signUp: (body: { email: string; password: string; name: string }) =>
    request<UserInfo>('/users', { method: 'POST', body }),
  async login(body: { email: string; password: string }) {
    const t = await request<TokenResponse>('/auth/login', { method: 'POST', body })
    tokenStore.set({ accessToken: t.accessToken, refreshToken: t.refreshToken })
  },
  async logout() {
    const t = tokenStore.get()
    tokenStore.set(null)
    if (t) {
      try {
        await request<void>('/auth/logout', { method: 'POST', body: { refreshToken: t.refreshToken } })
      } catch {
        // 서버 폐기에 실패해도 로컬 세션은 이미 지웠다
      }
    }
  },
  admin: {
    registerPerformance: (kopisId: string) =>
      request<{ id: number; kopisId: string; name: string; venueId: string }>('/admin/performances', {
        method: 'POST',
        body: { kopisId },
        auth: true,
      }),
    createSession: (performanceId: number | string, body: SessionBody) =>
      request<AdminSession>(`/admin/performances/${performanceId}/sessions`, { method: 'POST', body, auth: true }),
    updateSession: (id: number, body: Partial<SessionBody>) =>
      request<AdminSession>(`/admin/sessions/${id}`, { method: 'PATCH', body, auth: true }),
    deleteSession: (id: number) => request<void>(`/admin/sessions/${id}`, { method: 'DELETE', auth: true }),
    suspendSession: (id: number) => request<void>(`/admin/sessions/${id}/suspend`, { method: 'POST', auth: true }),
    syncKopis: () => request<{ runId: number }>('/admin/kopis/sync', { method: 'POST', auth: true }),
  },
  me: () => request<UserInfo>('/users/me', { auth: true }),
}
