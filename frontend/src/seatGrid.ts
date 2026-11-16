import type { SeatInfo, SeatStatus } from './api/types'

export const MAX_SEATS = 4
export const CELL = 14

export type SeatState = 'FREE' | 'HELD' | 'SOLD'

export interface Layout {
  seats: SeatInfo[]
  width: number
  height: number
  index: Map<number, SeatInfo>
  /** 셀 단위 버킷. 좌표 → 좌석 */
  buckets: Map<number, SeatInfo>
  cols: number
}

export function buildLayout(seats: SeatInfo[]): Layout {
  let maxX = 0
  let maxY = 0
  for (const s of seats) {
    if (s.x > maxX) maxX = s.x
    if (s.y > maxY) maxY = s.y
  }
  const cols = maxX + 1
  const buckets = new Map<number, SeatInfo>()
  const index = new Map<number, SeatInfo>()
  for (const s of seats) {
    buckets.set(s.y * cols + s.x, s)
    index.set(s.seatId, s)
  }
  return { seats, width: cols * CELL, height: (maxY + 1) * CELL, index, buckets, cols }
}

/** 화면 좌표(캔버스 기준 px)에 있는 좌석. 셀 안쪽 여백(1px)은 누르지 않은 것으로 본다. */
export function seatAt(layout: Layout, px: number, py: number): SeatInfo | null {
  if (px < 0 || py < 0) return null
  const x = Math.floor(px / CELL)
  const y = Math.floor(py / CELL)
  if (x >= layout.cols) return null
  return layout.buckets.get(y * layout.cols + x) ?? null
}

/** seat-status는 version(스냅샷 시각)이 더 작은 응답을 무시한다. 늦게 도착한 오래된 응답으로 되돌아가지 않기 위해서다. */
export function acceptStatus(current: SeatStatus | null, next: SeatStatus): boolean {
  return current === null || next.version >= current.version
}

export interface StatusSets {
  held: Set<number>
  sold: Set<number>
}

export function toSets(status: SeatStatus | null): StatusSets {
  return { held: new Set(status?.held ?? []), sold: new Set(status?.sold ?? []) }
}

export function stateOf(sets: StatusSets, seatId: number): SeatState {
  if (sets.sold.has(seatId)) return 'SOLD'
  if (sets.held.has(seatId)) return 'HELD'
  return 'FREE'
}

export type ToggleResult = { selected: number[]; rejected: 'LIMIT' | 'UNAVAILABLE' | null }

/** 선택 토글. 점유·판매 좌석은 고를 수 없고, 4매를 넘기면 거절한다. */
export function toggleSeat(selected: number[], seatId: number, state: SeatState): ToggleResult {
  if (selected.includes(seatId)) return { selected: selected.filter((id) => id !== seatId), rejected: null }
  if (state !== 'FREE') return { selected, rejected: 'UNAVAILABLE' }
  if (selected.length >= MAX_SEATS) return { selected, rejected: 'LIMIT' }
  return { selected: [...selected, seatId], rejected: null }
}

export function remainingSeconds(expiresAt: number, now: number): number {
  return Math.max(0, Math.ceil((expiresAt - now) / 1000))
}

export function formatCountdown(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60)
  const s = totalSeconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}
