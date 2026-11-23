// gateway(/api/v1)만 호출한다. 에러는 Problem Details의 code로 구분한다.

export interface ProblemDetail {
  title?: string
  status?: number
  code?: string
  detail?: string
  [extra: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  /** 에러 코드별 부가 필드(opensAt, seatIds 등) */
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`)
    this.status = status
    this.code = problem.code ?? 'UNKNOWN'
    this.problem = problem
  }
}

const MESSAGES: Record<string, string> = {
  EMAIL_DUPLICATED: '이미 가입된 이메일입니다.',
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다.',
  INVALID_REFRESH_TOKEN: '로그인이 만료되었습니다. 다시 로그인해 주세요.',
  INVALID_REQUEST: '입력값을 확인해 주세요.',
  NOT_FOUND: '찾을 수 없습니다.',
  FORBIDDEN: '관리자만 사용할 수 있습니다.',
  PERFORMANCE_NOT_FOUND: '공연을 찾을 수 없습니다.',
  PERFORMANCE_NOT_SYNCED: '상세 동기화가 끝나지 않은 공연입니다.',
  HALL_LAYOUT_UNAVAILABLE: '좌석 배치를 만들 수 없는 공연장입니다.',
  HALL_UNKNOWN: '공연장 정보를 알 수 없는 공연입니다.',
  HALL_NO_SEAT_SCALE: '공연장 객석 수가 없어 판매할 수 없는 공연입니다.',
  HALL_SEAT_SCALE_TOO_LARGE: '공연장 객석 수가 상한을 넘어 판매할 수 없는 공연입니다.',
  SESSION_NOT_FOUND: '회차를 찾을 수 없습니다.',
  SESSION_ALREADY_OPENED: '판매가 이미 열린 회차입니다.',
  SESSION_EDIT_LOCKED: '판매 오픈 10분 전부터는 입장 속도와 활성 상한만 수정할 수 있습니다.',
  PRICE_GRADE_MISMATCH: '가격 등급이 공연 등급표와 맞지 않습니다.',
  KOPIS_QUOTA_LOW: 'KOPIS 남은 호출 수가 부족합니다.',
  KOPIS_SYNC_IN_PROGRESS: 'KOPIS 동기화가 이미 실행 중입니다.',
  SALE_NOT_OPENED: '아직 판매가 시작되지 않았습니다.',
  SEAT_ALREADY_HELD: '다른 사람이 먼저 선택한 좌석입니다. 좌석 상태를 갱신했습니다.',
  PURCHASE_LIMIT_EXCEEDED: '회차당 최대 4매까지 예매할 수 있습니다.',
  SESSION_NOT_ON_SALE: '판매 중인 회차가 아닙니다.',
  HOLD_NOT_FOUND: '점유가 이미 해제되었습니다.',
  ENTRY_TOKEN_INVALID: '입장 시간이 만료되었습니다. 대기열에 다시 진입해 주세요.',
  HOLD_EXPIRED: '점유 시간이 끝났습니다. 좌석을 다시 선택해 주세요.',
  HOLD_EXPIRING: '점유 만료가 임박해 결제를 시작할 수 없습니다. 좌석을 다시 선택해 주세요.',
  ORDER_NOT_PAYABLE: '결제할 수 없는 주문입니다.',
  ORDER_ALREADY_EXISTS: '이미 주문이 만들어진 좌석입니다.',
  CANCEL_DEADLINE_PASSED: '취소 기한(공연 전날 23:59)이 지났습니다.',
  RESERVATION_NOT_FOUND: '예매를 찾을 수 없습니다.',
  SESSION_SCHEDULE_UNKNOWN: '회차 일정을 확인할 수 없어 지금은 취소할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return MESSAGES[e.code] ?? e.message
  return '네트워크 오류가 발생했습니다.'
}

const KEY = 'curtaincall.tokens'

export interface Tokens {
  accessToken: string
  refreshToken: string
}

export const tokenStore = {
  get(): Tokens | null {
    try {
      const raw = localStorage.getItem(KEY)
      return raw ? (JSON.parse(raw) as Tokens) : null
    } catch {
      return null
    }
  },
  set(t: Tokens | null) {
    try {
      if (t) localStorage.setItem(KEY, JSON.stringify(t))
      else localStorage.removeItem(KEY)
    } catch {
      // 저장소를 못 쓰는 환경에서는 새로고침 시 로그아웃된다
    }
    listeners.forEach((l) => l())
  },
}

const listeners = new Set<() => void>()
export function onTokenChange(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}

async function parseError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail = {}
  try {
    problem = (await res.json()) as ProblemDetail
  } catch {
    // 본문이 없거나 JSON이 아니면 상태 코드만 쓴다
  }
  return new ApiError(res.status, problem)
}

interface Options {
  method?: string
  body?: unknown
  auth?: boolean
  headers?: Record<string, string>
}

// 동시에 여러 요청이 401을 받아도 refresh는 한 번만 보낸다(refresh token은 한 번 쓰면 폐기된다).
// 토큰은 탭이 공유하는 localStorage에 있어서 다른 탭이 먼저 회전했을 수 있다. 보내기 전에 저장값을 다시 읽어
// 이미 바뀌었으면 refresh를 보내지 않고 저장된 토큰으로 재시도한다. 실패하면 이긴 탭이 새 토큰을 저장할 시간을
// 두고(서버는 회전 직후 유예 안의 재사용을 먼저 401로 돌려줄 수 있다) 저장값을 다시 확인해, 바뀌었으면 지우지 않는다.
const RECHECK_DELAY_MS = 500
let refreshing: Promise<boolean> | null = null

function refreshTokens(sent: Tokens | null): Promise<boolean> {
  refreshing ??= (async () => {
    const current = tokenStore.get()
    if (!current) return false
    if (sent && current.refreshToken !== sent.refreshToken) return true
    const res = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: current.refreshToken }),
    })
    if (!res.ok) {
      await new Promise((resolve) => setTimeout(resolve, RECHECK_DELAY_MS))
      const latest = tokenStore.get()
      if (latest && latest.refreshToken !== current.refreshToken) return true
      tokenStore.set(null)
      return false
    }
    const t = (await res.json()) as Tokens
    tokenStore.set({ accessToken: t.accessToken, refreshToken: t.refreshToken })
    return true
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

export async function request<T>(path: string, opts: Options = {}): Promise<T> {
  let sent: Tokens | null = null
  const send = () => {
    const headers: Record<string, string> = { ...opts.headers }
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
    const tokens = tokenStore.get()
    sent = tokens
    if (opts.auth && tokens) headers.Authorization = `Bearer ${tokens.accessToken}`
    return fetch(`/api/v1${path}`, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    })
  }
  let res = await send()
  if (res.status === 401 && opts.auth && (await refreshTokens(sent))) res = await send()
  if (!res.ok) throw await parseError(res)
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}
