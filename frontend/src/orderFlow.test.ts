import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ORDER_DETAIL_TEXT, cancelDeadline, checkoutStore, idempotency, isTerminal, orderDetailText, shouldPollReservation } from './orderFlow'

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

describe('shouldPollReservation', () => {
  it('취소 요청 상태에서만 폴링한다', () => {
    expect(shouldPollReservation('CANCEL_REQUESTED')).toBe(true)
    expect(shouldPollReservation('CONFIRMED')).toBe(false)
    expect(shouldPollReservation('CANCELLED')).toBe(false)
  })
})

describe('orderDetailText', () => {
  it('실패 사유 코드별 문구를 쓴다', () => {
    expect(orderDetailText('FAILED', 'DECLINED')).toContain('거절')
    expect(orderDetailText('FAILED', 'PG_UNAVAILABLE')).toContain('일시적')
    expect(orderDetailText('EXPIRED', 'HOLD_EXPIRED')).toContain('만료')
  })
  it('코드가 없거나 PAYMENT_FAILED·모르는 코드면 기존 문구', () => {
    expect(orderDetailText('FAILED', null)).toBe(ORDER_DETAIL_TEXT.FAILED)
    expect(orderDetailText('FAILED', 'PAYMENT_FAILED')).toBe(ORDER_DETAIL_TEXT.FAILED)
    expect(orderDetailText('FAILED', 'X')).toBe(ORDER_DETAIL_TEXT.FAILED)
    expect(orderDetailText('REFUNDED', null)).toBe(ORDER_DETAIL_TEXT.REFUNDED)
  })
})
