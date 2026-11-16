import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { errorMessage } from '../api/client'
import type { ReservationDetail } from '../api/types'
import { useAuth } from '../auth'
import { formatDateTime, won } from '../format'
import { RESERVATION_STATUS_TEXT, cancelDeadline, idempotency, shouldPollReservation } from '../orderFlow'

const POLL_MS = 2000

export default function ReservationPage() {
  const { id = '' } = useParams()
  const { user, loading } = useAuth()
  const [r, setR] = useState<ReservationDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [now] = useState(() => Date.now())

  const load = useCallback(() => api.reservation(id), [id])

  useEffect(() => {
    if (loading || !user) return
    let stale = false
    load()
      .then((d) => {
        if (stale) return
        setR(d)
        setError(null)
      })
      .catch((e) => !stale && setError(errorMessage(e)))
    return () => {
      stale = true
    }
  }, [load, loading, user])

  // 취소 요청 뒤(CANCEL_REQUESTED)에는 환불이 끝날 때까지 상태를 폴링한다
  const polling = r ? shouldPollReservation(r.status) : false
  useEffect(() => {
    if (!polling) return
    const t = setInterval(() => {
      load()
        .then((d) => {
          setR(d)
          setError(null)
        })
        .catch((e) => setError(errorMessage(e)))
    }, POLL_MS)
    return () => clearInterval(t)
  }, [polling, load])

  const cancel = async () => {
    if (!r || !window.confirm('예매를 취소하고 전액 환불받습니다. 계속할까요?')) return
    setBusy(true)
    setError(null)
    try {
      const res = await api.cancelReservation(r.reservationId, idempotency.keyFor(`cancel:${r.reservationId}`))
      setR({ ...r, status: res.status })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!loading && !user) return <p><Link to="/login">로그인</Link>이 필요합니다.</p>
  const deadline = r ? cancelDeadline(r.sessionStartAt) : null
  const pastDeadline = deadline !== null && deadline.getTime() < now

  return (
    <section>
      <p><Link to="/reservations">← 예매 내역</Link></p>
      <h1>예매 상세</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {!r && !error && <p>불러오는 중</p>}
      {r && deadline && (
        <>
          <p>
            <strong>{RESERVATION_STATUS_TEXT[r.status]}</strong> · 공연 {formatDateTime(r.sessionStartAt)} · {won(r.amount)}
          </p>
          <ul>
            {r.seats.map((s) => (
              <li key={s.seatId}>좌석 {s.seatId} · {s.grade} · {won(s.price)}</li>
            ))}
          </ul>
          {r.status === 'CONFIRMED' && (
            <>
              <p className="hint">
                {formatDateTime(deadline.toISOString())}까지 전액 환불 취소가 가능합니다(공연 전날 23:59).
              </p>
              <button disabled={busy || pastDeadline} onClick={() => void cancel()}>예매 취소</button>
              {pastDeadline && <p className="error">취소 기한이 지났습니다.</p>}
            </>
          )}
          {r.status === 'CANCEL_REQUESTED' && <p role="status">환불을 처리하고 있습니다. 잠시만 기다려 주세요.</p>}
          {r.status === 'CANCELLED' && <p role="status">취소되었고 결제 금액이 환불되었습니다.</p>}
        </>
      )}
    </section>
  )
}
