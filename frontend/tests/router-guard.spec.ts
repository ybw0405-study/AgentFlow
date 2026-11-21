import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type * as ApiModule from '@/api'

// 守卫在会话未就绪时会走 authApi.refresh()，这里拦掉真实网络请求。
vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    authApi: {
      ...actual.authApi,
      login: vi.fn(),
      refresh: vi.fn(),
      logout: vi.fn(),
      me: vi.fn(),
    },
  }
})

import { authApi } from '@/api'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

function session(role: string) {
  const auth = useAuthStore()
  auth.accessToken = 'tok'
  auth.user = {
    userId: 'u1', email: 'a@b.c', displayName: null,
    memberships: [{ tenantId: 's1', name: 'S1', type: 'SPACE', role }],
  }
  auth.currentSpaceId = 's1'
  auth.restored = true
  return auth
}

describe('admin 路由守卫', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // 单例 router 会跨用例保留 currentRoute，先回到 login 避免重复导航吞掉守卫。
    await router.replace({ name: 'login' })
  })

  it('/admin 路由声明了 meta.requiresAdmin', () => {
    const admin = router.getRoutes().find((r) => r.name === 'admin')
    expect(admin?.meta.requiresAdmin).toBe(true)
  })

  it('非管理员访问 /admin 被弹回工作台', async () => {
    expect(session('VIEWER').isAdmin).toBe(false)
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('workflows')
  })

  it('管理员访问 /admin 可以停留', async () => {
    expect(session('ADMIN').isAdmin).toBe(true)
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('OWNER 也算管理员，可进入 /admin', async () => {
    expect(session('OWNER').isAdmin).toBe(true)
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('未登录访问 /admin 先去登录页', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('401'))
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('login')
  })
})
