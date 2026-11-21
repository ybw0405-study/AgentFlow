import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { setSessionHooks } from '@/api/http'
import type { MeData } from '@/types/api'

interface AuthState {
  accessToken: string | null
  user: MeData | null
  currentSpaceId: string | null
  restored: boolean
  /** 已主动登出：拦截任何自动续期，避免退出后在途 401 把会话又「救」回来。 */
  signedOut: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    user: null,
    currentSpaceId: null,
    restored: false,
    signedOut: false,
  }),
  getters: {
    isAuthenticated: (s) => s.accessToken !== null,
    currentSpace: (s) =>
      s.user?.memberships.find((m) => m.tenantId === s.currentSpaceId) ?? null,
    /** 管理端入口判定：与 ConnectorsView 一致，取当前空间 membership.role。 */
    isAdmin: (s) => {
      const role = s.user?.memberships.find((m) => m.tenantId === s.currentSpaceId)?.role
      return role === 'OWNER' || role === 'ADMIN'
    },
  },
  actions: {
    async login(email: string, password: string): Promise<void> {
      const data = await authApi.login(email, password)
      // 重新登录成功后恢复正常的 401 自动续期；失败时保持原状态不误清。
      this.signedOut = false
      this.accessToken = data.accessToken
      await this.loadMe()
    },
    /**
     * 首次会话恢复（应用启动 / 路由守卫）。只尝试一次，避免每次导航都打刷新接口。
     */
    async restore(): Promise<boolean> {
      if (this.restored) return this.isAuthenticated
      const ok = await this.refreshSession()
      // 仅成功时置位：首屏 refresh 抖动失败仍保持 false，守卫下次导航可重试而不是卡死。
      if (ok) this.restored = true
      return ok
    },
    /**
     * 强制刷新 access token。与 {@link restore} 的区别是不受 restored 短路，
     * 专供请求拦截器在收到 401 后调用——否则 token 过期时会把「旧 token 仍存在」
     * 当成「会话有效」，重试必然再 401 且不会跳登录页。
     */
    async refreshSession(): Promise<boolean> {
      // 登出后一切自动续期都禁止：响应拦截器收到的在途 401 会走到这里，
      // 若放行就会用仍有效的刷新 Cookie 换回新 token，造成「退出后又被自动登录」。
      if (this.signedOut) return false
      try {
        const data = await authApi.refresh()
        // 刷新往返期间用户可能已登出，此时不能把新 token 写回本地。
        if (this.signedOut) return false
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
      // 先置位：logout 请求期间到达的其它在途 401 也不能续期。
      this.signedOut = true
      try {
        await authApi.logout()
      } finally {
        this.$reset()
        // $reset() 会把 signedOut 清回初始值，必须重新置位，否则登出后仍会被续期。
        this.signedOut = true
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
