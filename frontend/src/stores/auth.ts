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
    /**
     * 首次会话恢复（应用启动 / 路由守卫）。只尝试一次，避免每次导航都打刷新接口。
     */
    async restore(): Promise<boolean> {
      if (this.restored) return this.isAuthenticated
      this.restored = true
      return this.refreshSession()
    },
    /**
     * 强制刷新 access token。与 {@link restore} 的区别是不受 restored 短路，
     * 专供请求拦截器在收到 401 后调用——否则 token 过期时会把「旧 token 仍存在」
     * 当成「会话有效」，重试必然再 401 且不会跳登录页。
     */
    async refreshSession(): Promise<boolean> {
      try {
        const data = await authApi.refresh()
        this.accessToken = data.accessToken
        await this.loadMe()
        return true
      } catch {
        this.accessToken = null
        this.user = null
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
    refresh: () => useAuthStore().refreshSession(),
    onUnauthorized: () => {
      const store = useAuthStore()
      store.accessToken = null
      routerPushLogin()
    },
  })
}
