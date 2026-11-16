// gateway(/api/v1)만 호출한다. 에러는 Problem Details의 code로 구분한다.

export interface ProblemDetail {
  title?: string
  status?: number
  code?: string
  detail?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`)
    this.status = status
    this.code = problem.code ?? 'UNKNOWN'
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
  SESSION_NOT_FOUND: '회차를 찾을 수 없습니다.',
  SESSION_ALREADY_OPENED: '판매가 이미 열린 회차입니다.',
  SESSION_EDIT_LOCKED: '판매 오픈 10분 전부터는 입장 속도와 활성 상한만 수정할 수 있습니다.',
  PRICE_GRADE_MISMATCH: '가격 등급이 공연 등급표와 맞지 않습니다.',
  KOPIS_QUOTA_LOW: 'KOPIS 남은 호출 수가 부족합니다.',
  KOPIS_SYNC_IN_PROGRESS: 'KOPIS 동기화가 이미 실행 중입니다.',
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
}

// 동시에 여러 요청이 401을 받아도 refresh는 한 번만 보낸다(refresh token은 한 번 쓰면 폐기된다)
let refreshing: Promise<boolean> | null = null

function refreshTokens(): Promise<boolean> {
  refreshing ??= (async () => {
    const current = tokenStore.get()
    if (!current) return false
    const res = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: current.refreshToken }),
    })
    if (!res.ok) {
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
  const send = () => {
    const headers: Record<string, string> = {}
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
    const tokens = tokenStore.get()
    if (opts.auth && tokens) headers.Authorization = `Bearer ${tokens.accessToken}`
    return fetch(`/api/v1${path}`, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    })
  }
  let res = await send()
  if (res.status === 401 && opts.auth && (await refreshTokens())) res = await send()
  if (!res.ok) throw await parseError(res)
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}
