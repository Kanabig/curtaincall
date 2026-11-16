import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { ApiError, errorMessage } from '../api/client'
import type { Hold, SeatStatus } from '../api/types'
import SeatCanvas, { gradeColor } from '../components/SeatCanvas'
import { entryTokens } from '../entryToken'
import { won } from '../format'
import { holdStore } from '../holdStore'
import {
  MAX_SEATS,
  acceptStatus,
  buildLayout,
  formatCountdown,
  remainingSeconds,
  stateOf,
  toSets,
  toggleSeat,
} from '../seatGrid'
import type { SeatInfo } from '../api/types'

const STATUS_POLL_MS = 2000

export default function SeatPage() {
  const { sessionId = '' } = useParams()
  const navigate = useNavigate()
  const [seats, setSeats] = useState<SeatInfo[] | null>(null)
  const [status, setStatus] = useState<SeatStatus | null>(null)
  const statusRef = useRef<SeatStatus | null>(null)
  const [selected, setSelected] = useState<number[]>([])
  const [storedHold, setHold] = useState<Hold | null>(() => holdStore.get(sessionId))
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  const backToQueue = useCallback(
    (notice: string) => {
      entryTokens.clear(sessionId)
      holdStore.set(sessionId, null)
      navigate(`/sessions/${sessionId}/queue`, { replace: true, state: { notice } })
    },
    [sessionId, navigate],
  )

  // 입장 토큰이 없거나 만료 시각이 되면 대기열로 돌려보낸다
  useEffect(() => {
    const entry = entryTokens.get(sessionId)
    if (!entry) {
      backToQueue('입장 시간이 만료되었습니다. 대기열에 다시 진입해 주세요.')
      return
    }
    const t = setTimeout(
      () => backToQueue('입장 시간이 만료되었습니다. 대기열에 다시 진입해 주세요.'),
      Math.max(0, entry.expiresAt - Date.now()),
    )
    return () => clearTimeout(t)
  }, [sessionId, backToQueue])

  useEffect(() => {
    let stale = false
    api
      .seatMap(sessionId)
      .then((s) => !stale && setSeats(s))
      .catch((e) => !stale && setMessage(errorMessage(e)))
    return () => {
      stale = true
    }
  }, [sessionId])

  const refreshStatus = useCallback(async () => {
    try {
      const next = await api.seatStatus(sessionId)
      if (!acceptStatus(statusRef.current, next)) return
      statusRef.current = next
      setStatus(next)
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) setMessage(errorMessage(e))
      // 5xx·네트워크 오류는 다음 주기에 다시 가져온다
    }
  }, [sessionId])

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      if (!document.hidden) await refreshStatus()
      if (!stopped) timer = setTimeout(() => void tick(), STATUS_POLL_MS)
    }
    void tick()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [refreshStatus])

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  // 점유 TTL이 끝나면 화면에서도 해제된 것으로 본다
  const hold = storedHold && Date.parse(storedHold.expiresAt) > now ? storedHold : null
  const holdExpired = storedHold !== null && hold === null
  useEffect(() => {
    if (holdExpired) {
      holdStore.set(sessionId, null)
    }
  }, [holdExpired, sessionId])

  const layout = useMemo(() => (seats ? buildLayout(seats) : null), [seats])
  const sets = useMemo(() => toSets(status), [status])
  const selectedSet = useMemo(() => new Set(selected), [selected])
  const mine = useMemo(() => new Set(hold?.seatIds ?? []), [hold])
  const grades = useMemo(() => [...new Set((seats ?? []).map((s) => s.grade))], [seats])

  const onPick = useCallback(
    (seatId: number) => {
      if (!layout || hold) return
      const seat = layout.index.get(seatId)
      if (!seat) return
      const r = toggleSeat(selected, seatId, stateOf(sets, seatId))
      setSelected(r.selected)
      setMessage(
        r.rejected === 'LIMIT'
          ? `회차당 최대 ${MAX_SEATS}매까지 선택할 수 있습니다.`
          : r.rejected === 'UNAVAILABLE'
            ? '이미 선택할 수 없는 좌석입니다.'
            : null,
      )
    },
    [layout, hold, selected, sets],
  )

  const reserve = async () => {
    const entry = entryTokens.get(sessionId)
    if (!entry) return backToQueue('입장 시간이 만료되었습니다. 대기열에 다시 진입해 주세요.')
    setBusy(true)
    setMessage(null)
    try {
      const h = await api.hold(sessionId, selected, entry.token)
      holdStore.set(sessionId, h)
      setHold(h)
      setSelected([])
      setNow(Date.now())
      void refreshStatus()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'ENTRY_TOKEN_INVALID') {
        backToQueue(errorMessage(e))
        return
      }
      if (e instanceof ApiError && e.code === 'SEAT_ALREADY_HELD') {
        // 충돌한 좌석을 선택에서 빼고 판매 상태를 바로 다시 받는다
        const lost = new Set(Array.isArray(e.problem.seatIds) ? (e.problem.seatIds as number[]) : [])
        setSelected((prev) => prev.filter((id) => !lost.has(id)))
        void refreshStatus()
      }
      setMessage(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const release = async () => {
    if (!hold) return
    setBusy(true)
    try {
      await api.releaseHold(hold.holdId)
    } catch (e) {
      // 이미 만료·해제된 점유(404)는 풀린 것과 같다
      if (!(e instanceof ApiError && e.code === 'HOLD_NOT_FOUND')) setMessage(errorMessage(e))
    } finally {
      holdStore.set(sessionId, null)
      setHold(null)
      setBusy(false)
      void refreshStatus()
    }
  }

  if (!layout) return message ? <p role="alert" className="error">{message}</p> : <p>좌석 배치를 불러오는 중</p>

  const total = selected.reduce((sum, id) => sum + (layout.index.get(id)?.price ?? 0), 0)
  const entryLeft = remainingSeconds(entryTokens.get(sessionId)?.expiresAt ?? 0, now)

  return (
    <section>
      <p><Link to="/">← 목록</Link></p>
      <h1>좌석 선택</h1>
      <ul className="legend">
        {grades.map((g) => (
          <li key={g}><i style={{ background: gradeColor(grades, g) }} />{g} {won(layout.seats.find((s) => s.grade === g)?.price ?? 0)}</li>
        ))}
        <li><i style={{ background: '#d6336c' }} />내 선택</li>
        <li><i style={{ background: '#1a8f3c' }} />내 점유</li>
        <li><i style={{ background: '#e5c07b' }} />점유 중</li>
        <li><i style={{ background: '#9a9a9a' }} />판매됨</li>
      </ul>
      <p className="hint">입장 가능 시간 {formatCountdown(entryLeft)} · 한 회차에 최대 {MAX_SEATS}매</p>
      {holdExpired && <p role="alert" className="error">점유 시간이 끝나 좌석이 해제되었습니다.</p>}
      {message && <p role="alert" className="error">{message}</p>}
      <SeatCanvas layout={layout} sets={sets} selected={selectedSet} mine={mine} grades={grades} onPick={onPick} />
      <div className="seat-bar">
        {hold ? (
          <>
            <span>점유한 좌석 {hold.seatIds.length}석 · 남은 시간 <strong>{formatCountdown(remainingSeconds(Date.parse(hold.expiresAt), now))}</strong></span>
            <span>
              <button disabled={busy} onClick={() => void release()}>점유 해제</button>{' '}
              <button disabled={busy} onClick={() => navigate(`/sessions/${sessionId}/checkout`)}>결제하기</button>
            </span>
          </>
        ) : (
          <>
            <span>
              선택 {selected.length}/{MAX_SEATS}석 {selected.length > 0 && `· ${won(total)}`}
              {selected.length > 0 && ` (${selected.map((id) => seatLabel(layout.index.get(id))).join(', ')})`}
            </span>
            <button disabled={busy || selected.length === 0} onClick={() => void reserve()}>좌석 점유</button>
          </>
        )}
      </div>
    </section>
  )
}

function seatLabel(s: SeatInfo | undefined): string {
  return s ? `${s.floor}층 ${s.section}구역 ${s.row}열 ${s.number}번` : ''
}
