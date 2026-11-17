import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { FALLBACK_PRICES, InputError, defaultPrices, gradeDefaults, isEditLocked, parsePrices, pricesFromGrades, pricesToText, toLocalKst, toOffsetKst } from '../api/admin'
import { ApiError, errorMessage } from '../api/client'
import type { PerformanceDetail, PerformanceItem, SessionBody, SessionItem } from '../api/types'
import { useAuth } from '../auth'
import { SALE_LABEL, formatDateTime } from '../format'

export default function AdminPage() {
  const { user, loading } = useAuth()
  if (loading) return <p>불러오는 중</p>
  if (user?.role !== 'ADMIN') return <p role="alert" className="error">관리자만 접근할 수 있습니다.</p>
  return (
    <div className="admin">
      <h1>관리</h1>
      <SyncSection />
      <RegisterSection />
      <SessionSection />
    </div>
  )
}

function adminErrorMessage(e: unknown): string {
  // 등급 불일치는 어떤 등급이 틀렸는지 서버 detail에 담겨 온다
  if (e instanceof ApiError && e.code === 'PRICE_GRADE_MISMATCH' && e.problem.detail) {
    return `${errorMessage(e)} (${e.problem.detail})`
  }
  return errorMessage(e)
}

function useAction() {
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  async function run(fn: () => Promise<string>) {
    setBusy(true)
    setMsg(null)
    try {
      setMsg({ ok: true, text: await fn() })
    } catch (e) {
      setMsg({ ok: false, text: e instanceof InputError ? e.message : adminErrorMessage(e) })
    } finally {
      setBusy(false)
    }
  }
  const note = msg && <p role={msg.ok ? 'status' : 'alert'} className={msg.ok ? 'ok' : 'error'}>{msg.text}</p>
  return { busy, run, note }
}

function SyncSection() {
  const { busy, run, note } = useAction()
  return (
    <section>
      <h2>KOPIS 동기화</h2>
      <p className="hint">실행하면 KOPIS 호출 한도가 차감됩니다.</p>
      <button
        disabled={busy}
        onClick={() => void run(async () => `동기화를 시작했습니다. (실행 ${(await api.admin.syncKopis()).runId})`)}
      >
        수동 동기화
      </button>
      {note}
    </section>
  )
}

function RegisterSection() {
  const [kopisId, setKopisId] = useState('')
  const { busy, run, note } = useAction()
  function submit(e: FormEvent) {
    e.preventDefault()
    void run(async () => {
      const r = await api.admin.registerPerformance(kopisId.trim())
      return `등록: ${r.name} (공연 ID ${r.id})`
    })
  }
  return (
    <section>
      <h2>공연 등록</h2>
      <form onSubmit={submit}>
        <label>KOPIS 공연 ID<input value={kopisId} onChange={(e) => setKopisId(e.target.value)} placeholder="PF000000" required /></label>
        <button disabled={busy}>판매 공연으로 등록</button>
      </form>
      {note}
    </section>
  )
}

function SessionSection() {
  const [list, setList] = useState<PerformanceItem[]>([])
  const [perfId, setPerfId] = useState('')
  const [perf, setPerf] = useState<PerformanceDetail | null>(null)
  // 렌더 중에 시계를 읽지 않도록 불러온 시점의 시각을 들고 있는다
  const [now, setNow] = useState(0)
  const { busy, run, note } = useAction()

  useEffect(() => {
    api.admin.performances().then((p) => setList(p.content)).catch(() => setList([]))
  }, [])

  const load = (id: string) =>
    api.performance(id).then((d) => {
      setNow(Date.now())
      setPerf(d)
    })

  function choose(id: string) {
    setPerfId(id)
    setPerf(null)
    if (id) void run(async () => { await load(id); return '불러왔습니다.' })
  }
  const reload = async () => {
    if (perf) await load(String(perf.id))
  }

  return (
    <section>
      <h2>회차 관리</h2>
      <label>
        공연
        <select value={perfId} onChange={(e) => choose(e.target.value)} disabled={busy}>
          <option value="">공연을 선택하세요</option>
          {list.map((p) => (
            <option key={p.id} value={p.id}>{p.name} ({p.startDate})</option>
          ))}
        </select>
      </label>
      {note}
      {perf && (
        <>
          <h3>{perf.name}</h3>
          <ul className="sessions">
            {perf.sessions.map((s) => (
              <SessionRow key={s.id} s={s} now={now} onChanged={reload} />
            ))}
          </ul>
          <h3>회차 추가</h3>
          <SessionForm
            key={perf.id}
            now={now}
            defaultPrices={defaultPrices(perf.sessions)}
            grades={perf.grades}
            gradeValues={gradeDefaults(perf.grades, perf.sessions)}
            submitLabel="회차 등록"
            onSubmit={async (body) => {
              await api.admin.createSession(perf.id, body)
              await reload()
              return '회차를 등록했습니다.'
            }}
          />
        </>
      )}
    </section>
  )
}

function SessionRow({ s, now, onChanged }: { s: SessionItem; now: number; onChanged: () => Promise<void> }) {
  const [editing, setEditing] = useState(false)
  const { busy, run, note } = useAction()
  const opened = new Date(s.saleOpenAt).getTime() <= now
  return (
    <li>
      <div>
        <strong>{formatDateTime(s.startAt)}</strong>
        <span className={`sale ${s.saleState}`}>{SALE_LABEL[s.saleState]}</span>
        <span>오픈 {formatDateTime(s.saleOpenAt)}</span>
        <button onClick={() => setEditing(!editing)}>{editing ? '닫기' : '수정'}</button>
        <button
          disabled={busy || s.saleState !== 'ON_SALE'}
          onClick={() => void run(async () => { await api.admin.suspendSession(s.id); await onChanged(); return '판매를 중지했습니다.' })}
        >
          판매 중지
        </button>
        <button
          disabled={busy || opened}
          title={opened ? '판매 오픈 뒤에는 삭제할 수 없습니다' : undefined}
          onClick={() => void run(async () => { await api.admin.deleteSession(s.id); await onChanged(); return '삭제했습니다.' })}
        >
          삭제
        </button>
      </div>
      {note}
      {editing && (
        <SessionForm
          session={s}
          now={now}
          submitLabel="저장"
          onSubmit={async (body) => {
            await api.admin.updateSession(s.id, body)
            await onChanged()
            return '저장했습니다.'
          }}
        />
      )}
    </li>
  )
}

function SessionForm({
  session,
  now,
  defaultPrices: defaultText,
  grades = [],
  gradeValues = {},
  submitLabel,
  onSubmit,
}: {
  session?: SessionItem
  now: number
  defaultPrices?: string | null
  /** 새 회차 등록에서만 쓴다. 비어 있으면 가격을 텍스트로 입력한다 */
  grades?: string[]
  gradeValues?: Record<string, string>
  submitLabel: string
  onSubmit: (body: SessionBody) => Promise<string>
}) {
  const locked = session ? isEditLocked(session.saleOpenAt, now) : false
  const [startAt, setStartAt] = useState(session ? toLocalKst(session.startAt) : '')
  const [saleOpenAt, setSaleOpenAt] = useState(session ? toLocalKst(session.saleOpenAt) : '')
  const [prices, setPrices] = useState(session ? pricesToText(session.prices) : (defaultText ?? FALLBACK_PRICES))
  const byGrade = !session && grades.length > 0
  const [gradePrices, setGradePrices] = useState<Record<string, string>>(gradeValues)
  const [rate, setRate] = useState(session ? String(session.admissionPerSecond) : '100')
  const [maxActive, setMaxActive] = useState(session ? String(session.maxActiveUsers) : '1000')
  const { busy, run, note } = useAction()

  function submit(e: FormEvent) {
    e.preventDefault()
    void run(async () => {
      const parsed = byGrade ? pricesFromGrades(grades, gradePrices) : parsePrices(prices)
      if (!parsed) {
        throw new InputError(
          byGrade ? '모든 등급의 가격을 1원 이상의 숫자로 입력해 주세요.' : '가격은 "등급 금액" 형식으로 한 줄씩, 등급은 중복 없이 입력해 주세요.',
        )
      }
      const body: Partial<SessionBody> = {}
      if (!locked) {
        body.startAt = toOffsetKst(startAt)
        body.saleOpenAt = toOffsetKst(saleOpenAt)
        body.prices = parsed
      }
      body.admissionPerSecond = Number(rate)
      body.maxActiveUsers = Number(maxActive)
      return onSubmit(body as SessionBody)
    })
  }

  return (
    <form onSubmit={submit}>
      {locked && <p className="hint">판매 오픈 10분 전부터는 입장 속도와 활성 상한만 수정할 수 있습니다.</p>}
      <label>공연 일시(KST)<input type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} disabled={locked} required /></label>
      <label>판매 오픈(KST)<input type="datetime-local" value={saleOpenAt} onChange={(e) => setSaleOpenAt(e.target.value)} disabled={locked} required /></label>
      {byGrade ? (
        grades.map((g) => (
          <label key={g}>
            가격 {g}
            <input
              type="number"
              min={1}
              value={gradePrices[g] ?? ''}
              onChange={(e) => setGradePrices({ ...gradePrices, [g]: e.target.value })}
              required
            />
          </label>
        ))
      ) : (
        <label>등급별 가격<textarea rows={4} value={prices} onChange={(e) => setPrices(e.target.value)} disabled={locked} /></label>
      )}
      <label>초당 입장 수<input type="number" min={1} value={rate} onChange={(e) => setRate(e.target.value)} required /></label>
      <label>활성 사용자 상한<input type="number" min={1} value={maxActive} onChange={(e) => setMaxActive(e.target.value)} required /></label>
      <button disabled={busy}>{submitLabel}</button>
      {note}
    </form>
  )
}
