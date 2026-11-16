import { request, tokenStore } from './client'
import type {
  Page,
  PerformanceDetail,
  PerformanceFilter,
  PerformanceItem,
  TokenResponse,
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
  me: () => request<UserInfo>('/users/me', { auth: true }),
}
