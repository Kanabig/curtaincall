import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelDeadline, checkoutStore, idempotency, isTerminal } from './orderFlow'

function memoryStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
  }
}

describe('Idempotency-Key', () => {
  beforeEach(() => vi.stubGlobal('sessionStorage', memoryStorage()))
  afterEach(() => vi.unstubAllGlobals())

  it('같은 작업에는 같은 키, 다른 작업에는 다른 키를 준다', () => {
    const a = idempotency.keyFor('pay:o1')
    expect(idempotency.keyFor('pay:o1')).toBe(a)
    expect(idempotency.keyFor('pay:o2')).not.toBe(a)
  })

  it('저장소를 못 쓰면 매번 새 키지만 오류는 내지 않는다', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
    })
    expect(idempotency.keyFor('x')).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('점유에 대응하는 주문 ID를 기억한다', () => {
    expect(checkoutStore.get('h1')).toBeNull()
    checkoutStore.set('h1', 'o1')
    expect(checkoutStore.get('h1')).toBe('o1')
  })
})

describe('cancelDeadline', () => {
  it('공연 전날 23:59 KST', () => {
    // 2026-12-05 19:00 KST 공연 → 2026-12-04 23:59 KST
    expect(cancelDeadline('2026-12-05T19:00:00+09:00').toISOString()).toBe('2026-12-04T14:59:00.000Z')
  })
  it('자정 직후 공연도 KST 날짜 기준', () => {
    // 2026-12-05 00:30 KST = 2026-12-04T15:30Z
    expect(cancelDeadline('2026-12-04T15:30:00Z').toISOString()).toBe('2026-12-04T14:59:00.000Z')
  })
})

describe('isTerminal', () => {
  it('진행 중 상태는 폴링을 이어 간다', () => {
    expect(isTerminal('PAYMENT_REQUESTED')).toBe(false)
    expect(isTerminal('REFUNDING')).toBe(false)
    expect(isTerminal('REFUNDED')).toBe(true)
  })
})
