import type { PerformanceState, SaleState } from './api/types'

export const STATE_LABEL: Record<PerformanceState, string> = {
  UPCOMING: '공연예정',
  ONGOING: '공연중',
  ENDED: '공연완료',
}

export const SALE_LABEL: Record<SaleState, string> = {
  SCHEDULED: '판매 예정',
  ON_SALE: '판매 중',
  SUSPENDED: '판매 중지',
  CLOSED: '판매 종료',
}

export const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Seoul' })
}
