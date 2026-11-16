import { describe, expect, it } from 'vitest'
import { ApiError } from './api/client'
import { DEFAULT_RETRY_MS, isTerminalQueueError, retryDelay } from './queuePolling'

describe('isTerminalQueueError', () => {
  it.each([
    [404, 'SESSION_NOT_FOUND'],
    [409, 'SESSION_NOT_ON_SALE'],
    [403, 'FORBIDDEN'],
    [400, 'INVALID_REQUEST'],
  ])('%i %s 는 폴링을 멈춘다', (status, code) => {
    expect(isTerminalQueueError(new ApiError(status, { code }))).toBe(true)
  })

  it.each([
    [401, 'UNAUTHORIZED'],
    [429, 'TOO_MANY_REQUESTS'],
    [503, 'QUEUE_UNAVAILABLE'],
    [500, 'INTERNAL_ERROR'],
    [502, undefined],
    [408, undefined],
  ])('%i %s 는 계속 재시도한다', (status, code) => {
    expect(isTerminalQueueError(new ApiError(status, { code }))).toBe(false)
  })

  it('네트워크 오류는 계속 재시도한다', () => {
    expect(isTerminalQueueError(new TypeError('Failed to fetch'))).toBe(false)
  })
})

describe('retryDelay', () => {
  it('마지막 pollAfterMs를 쓴다', () => expect(retryDelay(5000)).toBe(5000))
  it('없으면 기본값', () => {
    expect(retryDelay()).toBe(DEFAULT_RETRY_MS)
    expect(retryDelay(0)).toBe(DEFAULT_RETRY_MS)
  })
})
