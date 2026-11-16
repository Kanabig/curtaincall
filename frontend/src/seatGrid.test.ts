import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SeatInfo } from './api/types'
import { entryTokens } from './entryToken'
import {
  CELL,
  acceptStatus,
  buildLayout,
  formatCountdown,
  remainingSeconds,
  seatAt,
  stateOf,
  toSets,
  toggleSeat,
} from './seatGrid'

function seat(seatId: number, x: number, y: number): SeatInfo {
  return { seatId, floor: 1, section: 'A', row: 'A', number: x + 1, x, y, grade: 'R', price: 100000, wheelchair: false }
}

/** 100열 × 100석 = 1만 석 */
function bigHall(): SeatInfo[] {
  const seats: SeatInfo[] = []
  for (let y = 0; y < 100; y++) for (let x = 0; x < 100; x++) seats.push(seat(y * 100 + x + 1, x, y))
  return seats
}

describe('seatGrid', () => {
  it('좌표로 좌석을 찾는다', () => {
    const layout = buildLayout([seat(1, 0, 0), seat(2, 2, 1)])
    expect(seatAt(layout, 1, 1)?.seatId).toBe(1)
    expect(seatAt(layout, 2 * CELL + 3, CELL + 3)?.seatId).toBe(2)
    expect(seatAt(layout, CELL + 3, 3)).toBeNull()
    expect(seatAt(layout, -1, 0)).toBeNull()
    expect(seatAt(layout, 99999, 0)).toBeNull()
  })

  it('version이 더 작은 판매 상태는 무시한다', () => {
    const cur = { version: 100, held: [], sold: [] }
    expect(acceptStatus(null, cur)).toBe(true)
    expect(acceptStatus(cur, { version: 99, held: [1], sold: [] })).toBe(false)
    expect(acceptStatus(cur, { version: 100, held: [1], sold: [] })).toBe(true)
  })

  it('판매 > 점유 > 가능 순으로 상태를 정한다', () => {
    const sets = toSets({ version: 1, held: [1, 2], sold: [2, 3] })
    expect([1, 2, 3, 4].map((id) => stateOf(sets, id))).toEqual(['HELD', 'SOLD', 'SOLD', 'FREE'])
  })

  it('선택은 4매까지, 점유·판매 좌석은 고를 수 없고, 다시 누르면 해제된다', () => {
    let sel: number[] = []
    for (const id of [1, 2, 3, 4]) sel = toggleSeat(sel, id, 'FREE').selected
    expect(toggleSeat(sel, 5, 'FREE')).toEqual({ selected: [1, 2, 3, 4], rejected: 'LIMIT' })
    expect(toggleSeat([1], 9, 'HELD')).toEqual({ selected: [1], rejected: 'UNAVAILABLE' })
    expect(toggleSeat(sel, 2, 'FREE').selected).toEqual([1, 3, 4])
  })

  it('남은 시간 표시', () => {
    expect(remainingSeconds(10_500, 10_000)).toBe(1)
    expect(remainingSeconds(10_000, 20_000)).toBe(0)
    expect(formatCountdown(299)).toBe('4:59')
  })

  it('1만 석 배치·판매 상태 병합·좌표 조회가 수 ms 안에 끝난다', () => {
    const seats = bigHall()
    const status = { version: 1, held: seats.slice(0, 3000).map((s) => s.seatId), sold: seats.slice(3000, 6000).map((s) => s.seatId) }
    const t0 = performance.now()
    const layout = buildLayout(seats)
    const sets = toSets(status)
    let held = 0
    for (const s of layout.seats) if (stateOf(sets, s.seatId) === 'HELD') held++
    let hit = 0
    for (let i = 0; i < 10_000; i++) if (seatAt(layout, (i % 100) * CELL + 2, Math.floor(i / 100) * CELL + 2)) hit++
    const ms = performance.now() - t0
    console.info(`seatGrid 10k: ${ms.toFixed(1)}ms`)
    expect(held).toBe(3000)
    expect(hit).toBe(10_000)
    expect(ms).toBeLessThan(200)
  })
})

describe('entryTokens', () => {
  const store = new Map<string, string>()
  vi.stubGlobal('sessionStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
  afterEach(() => store.clear())

  it('만료 전에는 돌려주고, 만료되면 지우고 null', () => {
    entryTokens.set('7', 'tok', '2026-12-01T20:10:00+09:00')
    const exp = Date.parse('2026-12-01T20:10:00+09:00')
    expect(entryTokens.get('7', exp - 1000)?.token).toBe('tok')
    expect(entryTokens.get('7', exp + 1)).toBeNull()
    expect(entryTokens.get('7', exp - 1000)).toBeNull()
  })

  it('회차별로 분리된다', () => {
    entryTokens.set('1', 'a', '2999-01-01T00:00:00Z')
    expect(entryTokens.get('2')).toBeNull()
  })
})
