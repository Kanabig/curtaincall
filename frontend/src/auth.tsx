import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from './api'
import { onTokenChange, tokenStore } from './api/client'
import type { UserInfo } from './api/types'

interface AuthState {
  user: UserInfo | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const Ctx = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [loading, setLoading] = useState(tokenStore.get() !== null)

  useEffect(() => {
    if (tokenStore.get()) {
      api
        .me()
        .then(setUser)
        .catch(() => setUser(null))
        .finally(() => setLoading(false))
    }
    // refresh 실패 등으로 토큰이 지워지면 화면도 로그아웃 상태로 맞춘다
    const off = onTokenChange(() => {
      if (!tokenStore.get()) setUser(null)
    })
    return () => {
      off()
    }
  }, [])

  const value: AuthState = {
    user,
    loading,
    login: async (email, password) => {
      await api.login({ email, password })
      setUser(await api.me())
    },
    logout: async () => {
      await api.logout()
      setUser(null)
    },
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAuth(): AuthState {
  const v = useContext(Ctx)
  if (!v) throw new Error('AuthProvider가 필요합니다')
  return v
}
