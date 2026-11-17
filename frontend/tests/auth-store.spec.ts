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

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('login loads memberships and picks first space', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900,
    })
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
  })
})
