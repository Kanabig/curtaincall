import type { OrderStatus, ReservationStatus } from './api/types'

// Idempotency-Key는 작업(점유로 주문, 주문으로 결제, 예매로 취소)마다 한 번 만들고
// 새로고침·재시도에도 같은 값을 쓴다. 탭에 보관한다.
export const idempotency = {
  keyFor(scope: string): string {
    const k = `curtaincall.idem.${scope}`
    try {
      const saved = sessionStorage.getItem(k)
      if (saved) return saved
      const fresh = crypto.randomUUID()
      sessionStorage.setItem(k, fresh)
      return fresh
    } catch {
      return crypto.randomUUID()
    }
  },
}

// 점유 → 주문 매핑. 새로고침 뒤 같은 점유로 주문을 다시 만들지 않게 한다.
export const checkoutStore = {
  get(holdId: string): string | null {
    try {
      return sessionStorage.getItem(`curtaincall.order.${holdId}`)
    } catch {
      return null
    }
  },
  set(holdId: string, orderId: string) {
    try {
      sessionStorage.setItem(`curtaincall.order.${holdId}`, orderId)
    } catch {
      // 저장소를 못 쓰면 같은 Idempotency-Key가 없어 새로고침 때 주문이 새로 만들어질 수 있다
    }
  },
}

export const TEST_CARDS = [
  { token: 'tok_approve', label: '승인', hint: '결제가 승인됩니다.' },
  { token: 'tok_decline', label: '거절', hint: '카드사가 거절합니다. 주문은 실패로 끝나고 좌석이 풀립니다.' },
  { token: 'tok_insufficient_funds', label: '잔액 부족', hint: '잔액 부족으로 거절됩니다.' },
] as const

const TERMINAL: OrderStatus[] = ['CONFIRMED', 'FAILED', 'REFUNDED', 'EXPIRED']
export const isTerminal = (s: OrderStatus) => TERMINAL.includes(s)

export const ORDER_STATUS_TEXT: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '결제 대기',
  PAYMENT_REQUESTED: '결제 처리 중',
  CONFIRMED: '예매 완료',
  FAILED: '결제 실패',
  REFUNDING: '환불 처리 중',
  REFUNDED: '환불 완료',
  EXPIRED: '시간 만료',
}

export const ORDER_DETAIL_TEXT: Partial<Record<OrderStatus, string>> = {
  PAYMENT_REQUESTED: '결제를 확인하고 있습니다. 이 화면을 닫아도 주문은 이어집니다.',
  FAILED: '결제가 승인되지 않아 주문이 취소되었습니다. 선택한 좌석은 해제되었습니다.',
  REFUNDING: '결제는 승인됐지만 좌석을 확보하지 못해 환불하고 있습니다.',
  REFUNDED:
    '결제는 승인됐지만 그사이 좌석이 다른 사람에게 넘어가 예매가 이루어지지 않았습니다. 결제 금액은 전액 환불되었습니다.',
  EXPIRED: '점유 시간 안에 결제를 시작하지 않아 주문이 만료되었습니다. 좌석을 다시 선택해 주세요.',
}

export const RESERVATION_STATUS_TEXT: Record<ReservationStatus, string> = {
  CONFIRMED: '예매 완료',
  CANCEL_REQUESTED: '취소 처리 중',
  CANCELLED: '취소됨',
}

/** 공연 전날 23:59(KST). 이 시각까지 전액 환불 취소가 된다. */
export function cancelDeadline(startAtIso: string): Date {
  const start = Date.parse(startAtIso)
  const kstDay = new Date(start + 9 * 3600_000)
  const y = kstDay.getUTCFullYear()
  const m = kstDay.getUTCMonth()
  const d = kstDay.getUTCDate()
  return new Date(Date.UTC(y, m, d - 1, 23, 59, 0) - 9 * 3600_000)
}
