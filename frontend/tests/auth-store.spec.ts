import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
  },
}))

import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

function me(role: string) {
  return {
    userId: 'u1', email: 'a@b.c', displayName: null,
    memberships: [{ tenantId: 's1', name: 'Space One', type: 'SPACE', role }],
  }
}

function token(accessToken: string) {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900 }
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('login loads memberships and picks first space', async () => {
    vi.mocked(authApi.login).mockResolvedValue(token('tok'))
    vi.mocked(authApi.me).mockResolvedValue({
      userId: 'u1', email: 'a@b.c', displayName: null,
      memberships: [
        { tenantId: 's1', name: 'Space One', type: 'SPACE', role: 'OWNER' },
        { tenantId: 's2', name: 'Space Two', type: 'SPACE', role: 'VIEWER' },
      ],
    })
    const store = useAuthStore()
    await store.login('a@b.c', 'pw')
    expect(store.accessToken).toBe('tok')
    expect(store.currentSpaceId).toBe('s1')
    expect(store.currentSpace?.role).toBe('OWNER')
  })

  it('restore fails cleanly when refresh rejected', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('401'))
    const store = useAuthStore()
    expect(await store.restore()).toBe(false)
    expect(store.isAuthenticated).toBe(false)
    expect(store.restored).toBe(false)
  })

  it('restore 只在成功时置位，失败后仍可重试', async () => {
    vi.mocked(authApi.refresh).mockRejectedValueOnce(new Error('网络抖动'))
    const store = useAuthStore()
    expect(await store.restore()).toBe(false)
    expect(store.restored).toBe(false)

    vi.mocked(authApi.refresh).mockResolvedValue(token('tok2'))
    vi.mocked(authApi.me).mockResolvedValue(me('VIEWER'))
    expect(await store.restore()).toBe(true)
    expect(store.restored).toBe(true)
    expect(store.isAuthenticated).toBe(true)
  })

  it('logout 后禁止自动续期，restore 也不会借刷新 Cookie 复活会话', async () => {
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    const store = useAuthStore()
    store.accessToken = 'tok'
    await store.logout()

    expect(store.signedOut).toBe(true)
    expect(store.isAuthenticated).toBe(false)
    expect(await store.refreshSession()).toBe(false)
    expect(authApi.refresh).not.toHaveBeenCalled()
    expect(authApi.me).not.toHaveBeenCalled()
    expect(await store.restore()).toBe(false)
    expect(store.accessToken).toBeNull()
  })

  it('刷新往返期间登出，也不会把新 token 写回本地', async () => {
    let resolveRefresh!: (v: ReturnType<typeof token>) => void
    vi.mocked(authApi.refresh).mockReturnValue(
      new Promise<ReturnType<typeof token>>((r) => { resolveRefresh = r }),
    )
    const store = useAuthStore()
    const pending = store.refreshSession()

    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    await store.logout()

    resolveRefresh(token('tok-new'))
    expect(await pending).toBe(false)
    expect(store.accessToken).toBeNull()
    expect(authApi.me).not.toHaveBeenCalled()
  })

  it('重新登录会清除 signedOut，恢复正常的 401 自动续期', async () => {
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    const store = useAuthStore()
    await store.logout()
    expect(store.signedOut).toBe(true)

    vi.mocked(authApi.login).mockResolvedValue(token('tok'))
    vi.mocked(authApi.me).mockResolvedValue(me('OWNER'))
    await store.login('a@b.c', 'pw')
    expect(store.signedOut).toBe(false)

    vi.mocked(authApi.refresh).mockResolvedValue(token('tok2'))
    expect(await store.refreshSession()).toBe(true)
    expect(authApi.refresh).toHaveBeenCalledOnce()
  })

  it('isAdmin 依据当前空间 membership.role 判定', () => {
    const store = useAuthStore()
    store.user = {
      userId: 'u1', email: 'a@b.c', displayName: null,
      memberships: [
        { tenantId: 's1', name: 'S1', type: 'SPACE', role: 'VIEWER' },
        { tenantId: 's2', name: 'S2', type: 'SPACE', role: 'ADMIN' },
      ],
    }
    store.currentSpaceId = 's1'
    expect(store.isAdmin).toBe(false)
    store.currentSpaceId = 's2'
    expect(store.isAdmin).toBe(true)
    store.user.memberships[1].role = 'OWNER'
    expect(store.isAdmin).toBe(true)
  })
})
