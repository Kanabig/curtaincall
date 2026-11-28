import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { Button, EmptyState } from '../components/ui'
import { errorMessage } from '../api/client'
import type { Page, PerformanceFilter, PerformanceItem } from '../api/types'
import { STATE_LABEL, won } from '../format'

const FIELDS = ['keyword', 'region', 'genre', 'from', 'to', 'state', 'maxPrice'] as const
const PAGE_SIZE = 20

// 필터와 페이지는 URL 쿼리에 둔다. 뒤로 가기·링크 공유가 그대로 동작한다
export default function PerformanceListPage() {
  const [params, setParams] = useSearchParams()
  const filter: PerformanceFilter = { page: Number(params.get('page') ?? 0) || 0 }
  for (const k of FIELDS) {
    const v = params.get(k)
    if (v) (filter as Record<string, unknown>)[k] = v
  }
  const query = params.toString()

  const [result, setResult] = useState<Page<PerformanceItem> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let stale = false
    api
      .performances(filter)
      .then((r) => {
        if (stale) return
        setResult(r)
        setError(null)
      })
      .catch((e) => !stale && setError(errorMessage(e)))
    return () => {
      stale = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  function search(form: FormData) {
    const next = new URLSearchParams()
    for (const k of FIELDS) {
      const v = String(form.get(k) ?? '').trim()
      if (v) next.set(k, v)
    }
    setParams(next)
  }

  function goto(page: number) {
    const next = new URLSearchParams(params)
    next.set('page', String(page))
    setParams(next)
  }

  const lastPage = result ? Math.max(0, Math.ceil(result.totalElements / (result.size || PAGE_SIZE)) - 1) : 0

  return (
    <>
      <form
        className="filters"
        key={query}
        onSubmit={(e) => {
          e.preventDefault()
          search(new FormData(e.currentTarget))
        }}
      >
        <input name="keyword" placeholder="공연명" defaultValue={params.get('keyword') ?? ''} />
        <input name="region" placeholder="지역 (예: 서울)" defaultValue={params.get('region') ?? ''} />
        <input name="genre" placeholder="장르 (예: 뮤지컬)" defaultValue={params.get('genre') ?? ''} />
        <label>시작<input name="from" type="date" defaultValue={params.get('from') ?? ''} /></label>
        <label>종료<input name="to" type="date" defaultValue={params.get('to') ?? ''} /></label>
        <select name="state" defaultValue={params.get('state') ?? ''}>
          <option value="">상태 전체</option>
          <option value="UPCOMING">{STATE_LABEL.UPCOMING}</option>
          <option value="ONGOING">{STATE_LABEL.ONGOING}</option>
          <option value="ENDED">{STATE_LABEL.ENDED}</option>
        </select>
        <input name="maxPrice" type="number" min={0} step={1000} placeholder="최고 가격(원)" defaultValue={params.get('maxPrice') ?? ''} />
        <Button variant="primary">검색</Button>
        <button type="button" onClick={() => setParams(new URLSearchParams())}>초기화</button>
      </form>

      {error && <p role="alert" className="error">{error}</p>}
      {!result && !error && <p>불러오는 중</p>}
      {result && result.content.length === 0 && <EmptyState title="조건에 맞는 공연이 없습니다." />}
      {result && (
        <ul className="grid">
          {result.content.map((p) => (
            <li key={p.id}>
              <Link to={`/performances/${p.id}`}>
                {p.poster ? <img src={p.poster} alt="" loading="lazy" /> : <div className="noposter" />}
                <strong>{p.name}</strong>
                <span>{[p.genre, p.area].filter(Boolean).join(' · ')}</span>
                <span>{p.facilityName}</span>
                <span>{p.startDate} ~ {p.endDate} · {STATE_LABEL[p.state]}</span>
                <span>{p.minPrice != null ? `${won(p.minPrice)}부터` : ''}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {result && result.totalElements > 0 && (
        <nav className="pager">
          <button disabled={result.page <= 0} onClick={() => goto(result.page - 1)}>이전</button>
          <span>{result.page + 1} / {lastPage + 1} ({result.totalElements}건)</span>
          <button disabled={result.page >= lastPage} onClick={() => goto(result.page + 1)}>다음</button>
        </nav>
      )}
    </>
  )
}
