import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { ApiError, errorMessage } from '../api/client'
import type { PerformanceDetail } from '../api/types'
import { Badge } from '../components/ui'
import { SALE_LABEL, SALE_TONE, STATE_LABEL, formatDateTime, won } from '../format'

export default function PerformanceDetailPage() {
  const { id = '' } = useParams()
  const [data, setData] = useState<PerformanceDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let stale = false
    api
      .performance(id)
      .then((d) => {
        if (stale) return
        setData(d)
        setError(null)
      })
      .catch((e) => !stale && setError(e instanceof ApiError && e.code === 'NOT_FOUND' ? '없는 공연입니다.' : errorMessage(e)))
    return () => {
      stale = true
    }
  }, [id])

  if (error) return <p role="alert" className="error">{error}</p>
  if (!data) return <p>불러오는 중</p>

  return (
    <article>
      <p><Link to="/">← 목록</Link></p>
      <div className="detail-head">
        {data.poster && <img src={data.poster} alt="" />}
        <div>
          <h1>{data.name}</h1>
          <dl>
            <dt>장르</dt><dd>{data.genre ?? '-'}</dd>
            <dt>공연장</dt><dd>{[data.area, data.facilityName].filter(Boolean).join(' ') || '-'}</dd>
            <dt>기간</dt><dd>{data.startDate} ~ {data.openRun ? '오픈런' : data.endDate} ({STATE_LABEL[data.state]})</dd>
            <dt>러닝타임</dt><dd>{data.runtime || '-'}</dd>
            <dt>관람 연령</dt><dd>{data.age || '-'}</dd>
          </dl>
        </div>
      </div>

      <h2>회차</h2>
      {data.sessions.length === 0 && <p>판매 중인 회차가 없습니다.</p>}
      <ul className="sessions">
        {data.sessions.map((s) => (
          <li key={s.id} className="card">
            <div>
              <strong>{formatDateTime(s.startAt)}</strong>
              <Badge tone={SALE_TONE[s.saleState]}>{SALE_LABEL[s.saleState]}</Badge>
              {s.saleState === 'SCHEDULED' && <span>오픈 {formatDateTime(s.saleOpenAt)}</span>}
              {(s.saleState === 'ON_SALE' || s.saleState === 'SCHEDULED') && (
                <Link to={`/sessions/${s.id}/queue`} className="btn btn-primary">{s.saleState === 'ON_SALE' ? '예매하기' : '대기열 입장'}</Link>
              )}
            </div>
            <table>
              <tbody>
                {s.prices.map((p) => (
                  <tr key={p.grade}><td>{p.grade}</td><td>{won(p.price)}</td></tr>
                ))}
              </tbody>
            </table>
          </li>
        ))}
      </ul>
      <p className="source">공연 정보 출처: 공연예정·상세 정보는 공연예술통합전산망(KOPIS). 회차·좌석·가격은 curtaincall 판매 정보입니다.</p>
    </article>
  )
}
