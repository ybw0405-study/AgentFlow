import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type * as ApiModule from '@/api'
import { ApiError } from '@/api/http'
import LoginView from '@/views/LoginView.vue'

const mocks = vi.hoisted(() => ({
  bootstrap: vi.fn(),
  login: vi.fn(),
  me: vi.fn(),
  push: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    authApi: {
      ...actual.authApi,
      bootstrap: mocks.bootstrap,
      login: mocks.login,
      me: mocks.me,
    },
  }
})

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  useRoute: () => ({ query: {} }),
}))

function mountLogin() {
  return mount(LoginView, { global: { plugins: [createPinia()] } })
}

describe('登录页', () => {
  beforeEach(() => {
    mocks.bootstrap.mockReset()
    mocks.login.mockReset()
    mocks.me.mockReset()
    mocks.push.mockReset()
  })

  it('展示后端下发的演示账号，点击后填入表单', async () => {
    mocks.bootstrap.mockResolvedValue({
      demoMode: true,
      demoAccounts: [
        { email: 'admin@agentflow.dev', password: 'agentflow123', label: '平台管理员', role: 'ADMIN' },
      ],
    })

    const wrapper = mountLogin()
    await flushPromises()

    const items = wrapper.findAll('.af-login__demo-item')
    expect(items).toHaveLength(1)
    expect(wrapper.text()).toContain('平台管理员')
    expect(wrapper.text()).toContain('admin@agentflow.dev')

    await items[0].trigger('click')
    const email = wrapper.find<HTMLInputElement>('#login-email')
    const password = wrapper.find<HTMLInputElement>('#login-password')
    expect(email.element.value).toBe('admin@agentflow.dev')
    expect(password.element.value).toBe('agentflow123')
  })

  it('演示环境关闭时不渲染账号区块', async () => {
    mocks.bootstrap.mockResolvedValue({ demoMode: false, demoAccounts: [] })

    const wrapper = mountLogin()
    await flushPromises()

    expect(wrapper.find('.af-login__demo').exists()).toBe(false)
  })

  it('bootstrap 失败不影响正常登录表单', async () => {
    mocks.bootstrap.mockRejectedValue(new Error('network'))

    const wrapper = mountLogin()
    await flushPromises()

    expect(wrapper.find('#login-email').exists()).toBe(true)
    expect(wrapper.find('.af-login__demo').exists()).toBe(false)
  })

  it('登录失败时给可读文案，并保留排查信息', async () => {
    mocks.bootstrap.mockResolvedValue({ demoMode: false, demoAccounts: [] })
    mocks.login.mockRejectedValue(
      new ApiError({
        status: 401,
        title: 'Unauthorized',
        detail: 'bad credentials',
        code: 'USR-AUTH-001',
        traceId: 'trace-9',
      } as never),
    )

    const wrapper = mountLogin()
    await flushPromises()

    await wrapper.find('#login-email').setValue('someone@example.com')
    await wrapper.find('#login-password').setValue('wrong-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const errorBox = wrapper.find('.af-login__error')
    expect(errorBox.exists()).toBe(true)

    // 正文只给人话，机器码与 traceId 收在次要行
    const mainLine = errorBox.find('.af-login__error-main')
    expect(mainLine.text()).toContain('邮箱或密码不正确')
    expect(mainLine.text()).not.toContain('USR-AUTH-001')

    const debugLine = errorBox.find('.af-login__debug')
    expect(debugLine.text()).toContain('USR-AUTH-001')
    expect(debugLine.text()).toContain('trace-9')
  })
})
