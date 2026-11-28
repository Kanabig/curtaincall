import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { ApiError, errorMessage } from '../api/client'
import type { OrderDetail } from '../api/types'
import { useAuth } from '../auth'
import { Button } from '../components/ui'
import { entryTokens } from '../entryToken'
import { won } from '../format'
import { holdStore } from '../holdStore'
import { TEST_CARDS, checkoutStore, idempotency } from '../orderFlow'
import { formatCountdown, remainingSeconds } from '../seatGrid'

export default function CheckoutPage() {
  const { sessionId = '' } = useParams()
  const { user, loading } = useAuth()
  const navigate = useNavigate()
  const [hold] = useState(() => holdStore.get(sessionId))
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [token, setToken] = useState<string>(TEST_CARDS[0].token)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  // 점유당 주문은 하나다. 새로고침해도 같은 Idempotency-Key·주문 ID를 쓴다
  useEffect(() => {
    if (loading || !user || !hold) return
    let stale = false
    const entry = entryTokens.get(sessionId)
    ;(async () => {
      let orderId = checkoutStore.get(hold.holdId)
      if (!orderId) {
        if (!entry) throw new ApiError(403, { code: 'ENTRY_TOKEN_INVALID' })
        const created = await api.createOrder(hold.holdId, entry.token, idempotency.keyFor(`order:${hold.holdId}`))
        orderId = created.orderId
        checkoutStore.set(hold.holdId, orderId)
      }
      const detail = await api.order(orderId)
      if (stale) return
      if (detail.status !== 'PENDING_PAYMENT') navigate(`/orders/${orderId}`, { replace: true })
      else setOrder(detail)
    })().catch((e) => !stale && setError(errorMessage(e)))
    return () => {
      stale = true
    }
  }, [loading, user, hold, sessionId, navigate])

  const pay = async () => {
    if (!order) return
    const entry = entryTokens.get(sessionId)
    if (!entry) return setError(errorMessage(new ApiError(403, { code: 'ENTRY_TOKEN_INVALID' })))
    setBusy(true)
    setError(null)
    try {
      await api.startPayment(order.orderId, token, entry.token, idempotency.keyFor(`pay:${order.orderId}`))
      navigate(`/orders/${order.orderId}`)
    } catch (e) {
      // 이미 시작된 결제(ORDER_NOT_PAYABLE)는 진행 화면에서 상태를 이어서 본다
      if (e instanceof ApiError && e.code === 'ORDER_NOT_PAYABLE') navigate(`/orders/${order.orderId}`)
      else setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!loading && !user) return <p><Link to="/login">로그인</Link>이 필요합니다.</p>
  if (!hold) {
    return (
      <section>
        <p role="alert" className="error">점유한 좌석이 없거나 점유 시간이 끝났습니다.</p>
        <p><Link to={`/sessions/${sessionId}/seats`}>좌석 선택으로</Link></p>
      </section>
    )
  }
  const left = remainingSeconds(Date.parse(hold.expiresAt), now)

  return (
    <section>
      <h1>결제</h1>
      <p className="hint">남은 점유 시간 <strong>{formatCountdown(left)}</strong> · 좌석 {hold.seatIds.length}석</p>
      {error && <p role="alert" className="error">{error}</p>}
      {!order && !error && <p>주문을 만드는 중</p>}
      {order && (
        <>
          <p>결제 금액 <strong>{won(order.amount)}</strong></p>
          <fieldset>
            <legend>테스트 카드(가짜 PG)</legend>
            {TEST_CARDS.map((c) => (
              <label key={c.token} className="radio">
                <input type="radio" name="card" checked={token === c.token} onChange={() => setToken(c.token)} />{' '}
                {c.label} <code>{c.token}</code> <span className="hint">{c.hint}</span>
              </label>
            ))}
          </fieldset>
          <p className="hint">실제 결제는 일어나지 않습니다. 카드 번호는 입력받지 않습니다.</p>
          <Button variant="primary" disabled={busy || left <= 0} onClick={() => void pay()}>{won(order.amount)} 결제</Button>
        </>
      )}
      <p><Link to={`/sessions/${sessionId}/seats`}>← 좌석 선택</Link></p>
    </section>
  )
}
