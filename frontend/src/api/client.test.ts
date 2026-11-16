import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request, tokenStore } from './client'

function memoryStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
  }
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('request: 401 → refresh', () => {
  const calls: { url: string; auth: string | null }[] = []
  let handler: (url: string, n: number) => Response

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage())
    tokenStore.set({ accessToken: 'old-access', refreshToken: 'old-refresh' })
    calls.length = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const auth = (init?.headers as Record<string, string> | undefined)?.Authorization ?? null
        calls.push({ url, auth })
        return handler(url, calls.length)
      }),
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  it('401이면 refresh로 토큰을 갈아 끼우고 원래 요청을 한 번 다시 보낸다', async () => {
    handler = (url, n) => {
      if (url === '/api/v1/auth/refresh') return json(200, { accessToken: 'new-access', refreshToken: 'new-refresh' })
      return n === 1 ? json(401, { code: 'UNAUTHORIZED' }) : json(200, { ok: true })
    }
    await expect(request('/users/me', { auth: true })).resolves.toEqual({ ok: true })
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/users/me', '/api/v1/auth/refresh', '/api/v1/users/me'])
    expect(calls[2].auth).toBe('Bearer new-access')
    expect(tokenStore.get()).toEqual({ accessToken: 'new-access', refreshToken: 'new-refresh' })
  })

  it('동시에 401을 받아도 refresh는 한 번만 보낸다', async () => {
    handler = (url) => {
      if (url === '/api/v1/auth/refresh') return json(200, { accessToken: 'a2', refreshToken: 'r2' })
      return calls.filter((c) => c.url === url).length <= 2 && calls.find((c) => c.auth === 'Bearer a2') === undefined
        ? json(401, {})
        : json(200, {})
    }
    await Promise.all([request('/users/me', { auth: true }), request('/reservations', { auth: true })])
    expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1)
  })

  it('refresh가 실패하면 토큰을 지우고 원래 401을 던진다', async () => {
    handler = (url) => (url === '/api/v1/auth/refresh' ? json(401, { code: 'INVALID_REFRESH_TOKEN' }) : json(401, { code: 'UNAUTHORIZED' }))
    await expect(request('/users/me', { auth: true })).rejects.toMatchObject({ status: 401 })
    expect(tokenStore.get()).toBeNull()
    expect(calls.filter((c) => c.url === '/api/v1/users/me')).toHaveLength(1)
  })

  it('auth가 아닌 요청은 401이어도 refresh하지 않는다', async () => {
    handler = () => json(401, { code: 'INVALID_CREDENTIALS' })
    await expect(request('/auth/login', { method: 'POST', body: {} })).rejects.toBeInstanceOf(ApiError)
    expect(calls).toHaveLength(1)
  })

  it('에러 응답의 부가 필드를 problem으로 노출한다', async () => {
    handler = () => json(409, { code: 'SEAT_ALREADY_HELD', seatIds: [7] })
    await expect(request('/x', { auth: true })).rejects.toMatchObject({ code: 'SEAT_ALREADY_HELD', problem: { seatIds: [7] } })
  })
})
