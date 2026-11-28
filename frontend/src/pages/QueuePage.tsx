import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { ApiError, errorMessage } from '../api/client'
import type { QueueEntry } from '../api/types'
import { useAuth } from '../auth'
import { entryTokens } from '../entryToken'
import { formatDateTime } from '../format'
import { isTerminalQueueError, retryDelay } from '../queuePolling'
import { formatCountdown, remainingSeconds } from '../seatGrid'

type View = { kind: 'loading' } | { kind: 'closed'; opensAt: number } | { kind: 'soldOut' } | { kind: 'queue'; entry: QueueEntry }

export default function QueuePage() {
  const { sessionId = '' } = useParams()
  const { user, loading } = useAuth()
  const navigate = useNavigate()
  const notice = (useLocation().state as { notice?: string } | null)?.notice
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (view.kind !== 'closed') return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [view.kind])

  useEffect(() => {
    if (loading || !user) return
    if (entryTokens.get(sessionId)) {
      navigate(`/sessions/${sessionId}/seats`, { replace: true })
      return
    }
    let stopped = false
    let lastPollAfterMs: number | undefined
    let timer: ReturnType<typeof setTimeout> | undefined

    const apply = (entry: QueueEntry) => {
      if (stopped) return
      setError(null)
      if (entry.status === 'ADMITTED') {
        entryTokens.set(sessionId, entry.entryToken, entry.expiresAt)
        navigate(`/sessions/${sessionId}/seats`, { replace: true })
      } else if (entry.status === 'EXPIRED') {
        void enter()
      } else {
        lastPollAfterMs = entry.pollAfterMs
        setView({ kind: 'queue', entry })
        timer = setTimeout(() => void poll(), entry.pollAfterMs)
      }
    }
    const fail = (e: unknown) => {
      if (stopped) return
      if (e instanceof ApiError && e.code === 'SALE_NOT_OPENED') {
        const opensAt = Date.parse(String(e.problem.opensAt))
        setView({ kind: 'closed', opensAt })
        // 오픈 시각이 되면 자동으로 다시 진입한다
        timer = setTimeout(() => void enter(), Math.max(1000, opensAt - Date.now()))
        return
      }
      if (e instanceof ApiError && e.code === 'SESSION_SOLD_OUT') {
        // 대기열에 없던 사용자는 매진 중에 들어갈 수 없다. 다시 시도하지 않는다
        setError(null)
        setView({ kind: 'soldOut' })
        return
      }
      setError(errorMessage(e))
      // 복구할 수 없는 코드가 아니면 순번을 잃지 않으므로 계속 폴링한다.
      // 401은 client가 재발급을 시도하고, 재발급이 실패하면 로그아웃되어 이 effect가 정리된다
      if (!isTerminalQueueError(e)) timer = setTimeout(() => void poll(), retryDelay(lastPollAfterMs))
    }
    async function enter() {
      try {
        apply(await api.enterQueue(sessionId))
      } catch (e) {
        fail(e)
      }
    }
    async function poll() {
      try {
        apply(await api.queueStatus(sessionId))
      } catch (e) {
        fail(e)
      }
    }
    void enter()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [sessionId, user, loading, navigate])

  if (loading) return <p>불러오는 중</p>
  if (!user) return <p>대기열에 들어가려면 <Link to="/login">로그인</Link>해 주세요.</p>

  const leave = async () => {
    try {
      await api.leaveQueue(sessionId)
    } catch {
      // 폴링을 멈추면 서버가 60초 뒤 정리한다
    }
    navigate(-1)
  }

  return (
    <section className="queue">
      <h1>대기열</h1>
      {notice && <p role="status" className="error">{notice}</p>}
      {error && <p role="alert" className="error">{error}</p>}
      {view.kind === 'loading' && <p>대기열에 진입하는 중</p>}
      {view.kind === 'closed' && (
        <>
          <p>판매 오픈 {formatDateTime(new Date(view.opensAt).toISOString())}</p>
          <p className="big">{formatCountdown(remainingSeconds(view.opensAt, now))}</p>
          <p>오픈 시각이 되면 자동으로 대기열에 진입합니다.</p>
        </>
      )}
      {view.kind === 'soldOut' && <p className="big">매진되었습니다</p>}
      {view.kind === 'queue' && view.entry.status === 'WAITING' && (
        <>
          <p className="big">내 순번 {view.entry.position.toLocaleString()}번</p>
          <p>앞에 {view.entry.ahead.toLocaleString()}명 · 예상 대기 {formatEta(view.entry.etaSeconds)}</p>
          <p className="hint">이 창을 닫거나 60초 넘게 응답이 없으면 대기에서 빠집니다.</p>
        </>
      )}
      {view.kind === 'queue' && view.entry.status === 'SOLD_OUT' && (
        <>
          <p className="big">매진되었습니다</p>
          <p>순번 {view.entry.position.toLocaleString()}번을 유지합니다. 취소 표가 나오면 순서대로 입장합니다.</p>
        </>
      )}
      <p><button onClick={() => void leave()}>대기 포기</button></p>
    </section>
  )
}

function formatEta(seconds: number): string {
  if (seconds < 60) return `${seconds}초`
  return `약 ${Math.ceil(seconds / 60)}분`
}
