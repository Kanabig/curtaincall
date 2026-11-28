import { ApiError } from './api/client'

// 같은 요청을 다시 보내도 결과가 바뀌지 않는 코드. 이 코드에서만 폴링을 멈춘다.
const TERMINAL_CODES = new Set(['SESSION_NOT_FOUND', 'SESSION_NOT_ON_SALE', 'SESSION_SOLD_OUT', 'FORBIDDEN', 'BAD_REQUEST', 'INVALID_REQUEST'])

export const DEFAULT_RETRY_MS = 3000

export function isTerminalQueueError(e: unknown): boolean {
  return e instanceof ApiError && TERMINAL_CODES.has(e.code)
}

/** 일시 오류 뒤 다음 시도까지의 간격. 서버가 알려 준 pollAfterMs를 우선한다. */
export function retryDelay(lastPollAfterMs?: number): number {
  return lastPollAfterMs && lastPollAfterMs > 0 ? lastPollAfterMs : DEFAULT_RETRY_MS
}
