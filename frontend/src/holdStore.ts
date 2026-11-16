import type { Hold } from './api/types'

// 새로고침해도 점유 정보를 잃지 않도록 탭에 보관한다. 만료 시각이 지나면 버린다.
const key = (sessionId: string) => `curtaincall.hold.${sessionId}`

export const holdStore = {
  get(sessionId: string, now = Date.now()): Hold | null {
    try {
      const raw = sessionStorage.getItem(key(sessionId))
      if (!raw) return null
      const h = JSON.parse(raw) as Hold
      if (!(Date.parse(h.expiresAt) > now)) {
        sessionStorage.removeItem(key(sessionId))
        return null
      }
      return h
    } catch {
      return null
    }
  },
  set(sessionId: string, hold: Hold | null) {
    try {
      if (hold) sessionStorage.setItem(key(sessionId), JSON.stringify(hold))
      else sessionStorage.removeItem(key(sessionId))
    } catch {
      // 저장소를 못 쓰면 새로고침 때 점유 표시만 잃는다(점유 자체는 TTL로 풀린다)
    }
  },
}
