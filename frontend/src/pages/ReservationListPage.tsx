import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { errorMessage } from '../api/client'
import type { ReservationPage } from '../api/types'
import { useAuth } from '../auth'
import { EmptyState } from '../components/ui'
import { formatDateTime, won } from '../format'
import { RESERVATION_STATUS_TEXT } from '../orderFlow'

export default function ReservationListPage() {
  const { user, loading } = useAuth()
  const [page, setPage] = useState(0)
  const [data, setData] = useState<ReservationPage | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (loading || !user) return
    let stale = false
    api
      .reservations(page)
      .then((d) => !stale && setData(d))
      .catch((e) => !stale && setError(errorMessage(e)))
    return () => {
      stale = true
    }
  }, [page, loading, user])

  if (!loading && !user) return <p><Link to="/login">로그인</Link>이 필요합니다.</p>
  return (
    <section>
      <h1>예매 내역</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {!data && !error && <p>불러오는 중</p>}
      {data && data.items.length === 0 && <EmptyState title="예매 내역이 없습니다." />}
      {data && data.items.length > 0 && (
        <ul>
          {data.items.map((r) => (
            <li key={r.reservationId}>
              <Link to={`/reservations/${r.reservationId}`}>
                {formatDateTime(r.sessionStartAt)} · {r.seatIds.length}석 · {won(r.amount)}
              </Link>{' '}
              <strong>{RESERVATION_STATUS_TEXT[r.status]}</strong>
            </li>
          ))}
        </ul>
      )}
      {data && data.totalPages > 1 && (
        <p>
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>{' '}
          {page + 1} / {data.totalPages}{' '}
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>다음</button>
        </p>
      )}
    </section>
  )
}
