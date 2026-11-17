import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { setSessionHooks } from '@/api/http'
import type { MeData } from '@/types/api'

interface AuthState {
  accessToken: string | null
  user: MeData | null
  currentSpaceId: string | null
  restored: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    user: null,
    currentSpaceId: null,
    restored: false,
  }),
  getters: {
    isAuthenticated: (s) => s.accessToken !== null,
    currentSpace: (s) =>
      s.user?.memberships.find((m) => m.tenantId === s.currentSpaceId) ?? null,
  },
  actions: {
    async login(email: string, password: string): Promise<void> {
      const data = await authApi.login(email, password)
      this.accessToken = data.accessToken
      await this.loadMe()
    },
    async restore(): Promise<boolean> {
      if (this.restored) return this.isAuthenticated
      this.restored = true
      try {
        const data = await authApi.refresh()
        this.accessToken = data.accessToken
        await this.loadMe()
        return true
      } catch {
        return false
      }
    },
    async loadMe(): Promise<void> {
      this.user = await authApi.me()
      if (!this.currentSpaceId && this.user.memberships.length) {
        this.currentSpaceId = this.user.memberships[0].tenantId
      }
    },
    async logout(): Promise<void> {
      try {
        await authApi.logout()
      } finally {
        this.$reset()
        this.restored = true
      }
    },
    setSpace(tenantId: string): void {
      this.currentSpaceId = tenantId
    },
  },
})

/** 在 main.ts 中调用一次：把会话能力注入 axios 拦截器。 */
export function wireSessionHooks(routerPushLogin: () => void): void {
  setSessionHooks({
    getAccessToken: () => useAuthStore().accessToken,
    getSpaceId: () => useAuthStore().currentSpaceId,
    refresh: () => useAuthStore().restore(),
    onUnauthorized: () => {
      const store = useAuthStore()
      store.accessToken = null
      routerPushLogin()
    },
  })
}
