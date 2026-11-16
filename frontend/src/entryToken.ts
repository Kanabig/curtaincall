// 입장 토큰은 회차별로 탭(sessionStorage)에 보관한다. 만료되면 쓰지 않고 지운다.
const key = (sessionId: string) => `curtaincall.entry.${sessionId}`

export interface StoredEntry {
  token: string
  expiresAt: number
}

export const entryTokens = {
  get(sessionId: string, now = Date.now()): StoredEntry | null {
    try {
      const raw = sessionStorage.getItem(key(sessionId))
      if (!raw) return null
      const v = JSON.parse(raw) as StoredEntry
      if (typeof v.token !== 'string' || !(v.expiresAt > now)) {
        sessionStorage.removeItem(key(sessionId))
        return null
      }
      return v
    } catch {
      return null
    }
  },
  set(sessionId: string, token: string, expiresAtIso: string) {
    try {
      sessionStorage.setItem(key(sessionId), JSON.stringify({ token, expiresAt: Date.parse(expiresAtIso) }))
    } catch {
      // 저장소를 못 쓰면 새로고침 때 대기열로 돌아간다
    }
  },
  clear(sessionId: string) {
    try {
      sessionStorage.removeItem(key(sessionId))
    } catch {
      // 무시
    }
  },
}
