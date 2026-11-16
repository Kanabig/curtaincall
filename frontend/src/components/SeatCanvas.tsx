import { useCallback, useEffect, useRef } from 'react'
import { CELL, seatAt, stateOf, type Layout, type StatusSets } from '../seatGrid'

// 1만 석을 DOM으로 그리면 노드가 1만 개라 스크롤·상태 갱신이 느려져 canvas 한 장에 그린다.
const GRADE_COLORS = ['#7b5ea7', '#2f7fc1', '#2e9e6b', '#c28a1f', '#c25b1f']

interface Props {
  layout: Layout
  sets: StatusSets
  selected: ReadonlySet<number>
  mine: ReadonlySet<number>
  grades: string[]
  onPick: (seatId: number) => void
}

export function gradeColor(grades: string[], grade: string): string {
  const i = grades.indexOf(grade)
  return GRADE_COLORS[(i < 0 ? 0 : i) % GRADE_COLORS.length]
}

export default function SeatCanvas({ layout, sets, selected, mine, grades, onPick }: Props) {
  const ref = useRef<HTMLCanvasElement>(null)

  const draw = useCallback(() => {
    const canvas = ref.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    const t0 = performance.now()
    const dpr = window.devicePixelRatio || 1
    canvas.width = layout.width * dpr
    canvas.height = layout.height * dpr
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, layout.width, layout.height)
    const size = CELL - 2
    for (const s of layout.seats) {
      const state = stateOf(sets, s.seatId)
      ctx.fillStyle = mine.has(s.seatId)
        ? '#1a8f3c'
        : selected.has(s.seatId)
          ? '#d6336c'
          : state === 'SOLD'
            ? '#9a9a9a'
            : state === 'HELD'
              ? '#e5c07b'
              : gradeColor(grades, s.grade)
      ctx.fillRect(s.x * CELL + 1, s.y * CELL + 1, size, size)
    }
    // 측정용: 개발자 도구에서 performance.getEntriesByName('seat-draw')로 확인한다
    performance.measure('seat-draw', { start: t0, end: performance.now() })
  }, [layout, sets, selected, mine, grades])

  useEffect(() => {
    draw()
  }, [draw])

  return (
    <div className="seat-scroll">
      <canvas
        ref={ref}
        style={{ width: layout.width, height: layout.height }}
        aria-label="좌석 배치도"
        onClick={(e) => {
          const r = e.currentTarget.getBoundingClientRect()
          const seat = seatAt(layout, e.clientX - r.left, e.clientY - r.top)
          if (seat) onPick(seat.seatId)
        }}
      />
    </div>
  )
}
