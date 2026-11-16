import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { ApiError, errorMessage } from '../api/client'
import type { OrderDetail } from '../api/types'
import { useAuth } from '../auth'
import { entryTokens } from '../entryToken'
import { won } from '../format'
import { holdStore } from '../holdStore'
import { ORDER_STATUS_TEXT, isTerminal, orderDetailText } from '../orderFlow'

const POLL_MS = 1500

export default function OrderPage() {
  const { orderId = '' } = useParams()
  const { user, loading } = useAuth()
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  // 주문 ID는 주소에 있어 새로고침해도 같은 주문의 상태를 이어서 본다
  useEffect(() => {
    if (loading || !user) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const poll = async () => {
      try {
        const o = await api.order(orderId)
        if (stopped) return
        setOrder(o)
        setError(null)
        if (isTerminal(o.status)) {
          holdStore.set(String(o.sessionId), null)
          if (o.status === 'CONFIRMED') entryTokens.clear(String(o.sessionId))
          return
        }
      } catch (e) {
        if (stopped) return
        setError(errorMessage(e))
        // 404 등 4xx는 재시도해도 같다. 5xx·네트워크 오류는 계속 폴링한다
        if (e instanceof ApiError && e.status < 500 && e.status !== 401) return
      }
      timer = setTimeout(() => void poll(), POLL_MS)
    }
    void poll()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [orderId, loading, user])

  if (!loading && !user) return <p><Link to="/login">로그인</Link>이 필요합니다.</p>
  const detail = order ? orderDetailText(order.status, order.failureCode) : undefined
  const bad = order && ['FAILED', 'REFUNDED', 'EXPIRED', 'REFUNDING'].includes(order.status)

  return (
    <section>
      <h1>주문 {order ? ORDER_STATUS_TEXT[order.status] : ''}</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {!order && !error && <p>주문 상태를 불러오는 중</p>}
      {order && (
        <>
          <p>{order.seatIds.length}석 · {won(order.amount)}</p>
          {detail && <p role={bad ? 'alert' : 'status'} className={bad ? 'error' : undefined}>{detail}</p>}
          {order.status === 'PENDING_PAYMENT' && (
            <p><Link to={`/sessions/${order.sessionId}/checkout`}>결제 화면으로</Link></p>
          )}
          {order.status === 'CONFIRMED' && (
            <p>예매가 완료되었습니다. <Link to="/reservations">예매 내역 보기</Link></p>
          )}
          {(order.status === 'FAILED' || order.status === 'REFUNDED' || order.status === 'EXPIRED') && (
            <p><Link to={`/sessions/${order.sessionId}/queue`}>다시 예매하기</Link></p>
          )}
        </>
      )}
    </section>
  )
}
