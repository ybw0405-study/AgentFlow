import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import ConnectorsView from '@/views/ConnectorsView.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  authorize: vi.fn(),
  probeModels: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    connectorApi: {
      list: mocks.list,
      create: mocks.create,
      update: mocks.update,
      remove: mocks.remove,
      authorize: mocks.authorize,
      probeModels: mocks.probeModels,
    },
  }
})

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { memberships: [{ tenantId: 'space-alpha', role: 'ADMIN' }] },
    currentSpaceId: 'space-alpha',
  }),
}))

async function mountView() {
  const wrapper = mount(ConnectorsView)
  await flushPromises()
  return wrapper
}

/** 表单字段多且同类 input 复用，按 placeholder 定位比按下标稳。 */
function field(wrapper: Awaited<ReturnType<typeof mountView>>, placeholderStart: string) {
  const el = wrapper.findAll('input').find((i) => (i.attributes('placeholder') ?? '').startsWith(placeholderStart))
  if (!el) throw new Error(`no input starting with ${placeholderStart}`)
  return el
}

async function fillLlmForm(wrapper: Awaited<ReturnType<typeof mountView>>): Promise<void> {
  await field(wrapper, 'deepseek').setValue('deepseek')
  await field(wrapper, 'https://api.deepseek.com/v1').setValue('https://api.deepseek.com/v1')
  await field(wrapper, 'sk-...').setValue('sk-live-key')
}

function createButton(wrapper: Awaited<ReturnType<typeof mountView>>) {
  return wrapper.findAll('button').find((b) => b.text().includes('新建模型连接'))
}

describe('连接器页面：模型接入', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((m) => m.mockReset())
    mocks.list.mockResolvedValue([])
    mocks.update.mockResolvedValue({})
    mocks.authorize.mockResolvedValue(undefined)
    mocks.probeModels.mockResolvedValue({ ok: true, models: ['deepseek-chat'], errorCode: null })
  })

  it('默认就是模型接入，按钮文案对应', async () => {
    const wrapper = await mountView()
    expect(createButton(wrapper)).toBeTruthy()
    expect(wrapper.text()).toContain('模型接入（OpenAI 兼容）')
  })

  it('建模型连接：建→存密钥→拉模型→回写清单', async () => {
    mocks.create.mockResolvedValue({ id: 'c-new', name: 'deepseek' })
    mocks.probeModels.mockResolvedValue({
      ok: true,
      models: ['deepseek-chat', 'deepseek-reasoner'],
      errorCode: null,
    })

    const wrapper = await mountView()
    await fillLlmForm(wrapper)
    await createButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(mocks.create).toHaveBeenCalledTimes(1)
    const body = mocks.create.mock.calls[0][0]
    expect(body).toMatchObject({ name: 'deepseek', type: 'LLM', authType: 'API_KEY' })
    expect(body.config.baseUrl).toBe('https://api.deepseek.com/v1')
    // 允许域名留空时从 Base URL 推导，省掉一次手填
    expect(body.config.allowedHosts).toEqual(['api.deepseek.com'])
    expect(body.config.auth).toEqual({ headerName: 'Authorization', valuePrefix: 'Bearer ' })
    expect(body.config.operations).toEqual({
      chat: { method: 'POST', path: '/chat/completions' },
      models: { method: 'GET', path: '/models' },
    })

    expect(mocks.authorize).toHaveBeenCalledWith('c-new', 'sk-live-key')
    expect(mocks.probeModels).toHaveBeenCalledWith('c-new')
    expect(mocks.update).toHaveBeenCalledWith('c-new', {
      config: expect.objectContaining({
        models: ['deepseek-chat', 'deepseek-reasoner'],
        defaultModel: 'deepseek-chat',
      }),
    })
    expect(wrapper.text()).toContain('可选用 2 个模型')
  })

  it('拉取失败时给出可操作的原因，而不是静默成功', async () => {
    mocks.create.mockResolvedValue({ id: 'c-new', name: 'deepseek' })
    mocks.probeModels.mockResolvedValue({ ok: false, models: [], errorCode: 'CONN-HTTP-4XX' })

    const wrapper = await mountView()
    await fillLlmForm(wrapper)
    await createButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(mocks.update).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('检查 Base URL 路径与 API Key')
  })

  it('没填密钥时不探测，避免把缺凭据当成端点故障', async () => {
    mocks.create.mockResolvedValue({ id: 'c-new', name: 'deepseek' })

    const wrapper = await mountView()
    await field(wrapper, 'deepseek').setValue('deepseek')
    await field(wrapper, 'https://api.deepseek.com/v1').setValue('https://api.deepseek.com/v1')
    await createButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(mocks.probeModels).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已创建 deepseek')
  })

  it('切到 HTTP 工具时不再写模型相关配置', async () => {
    mocks.create.mockResolvedValue({ id: 'c-tool', name: 'crm' })

    const wrapper = await mountView()
    await wrapper.find('select').setValue('HTTP')
    await field(wrapper, 'crm').setValue('crm')
    await field(wrapper, 'https://api.example.com').setValue('https://api.example.com')
    const btn = wrapper.findAll('button').find((b) => b.text().includes('新建连接器'))
    await btn!.trigger('click')
    await flushPromises()

    const body = mocks.create.mock.calls[0][0]
    expect(body.type).toBe('HTTP')
    expect(body.config.operations).toEqual({})
    expect(mocks.probeModels).not.toHaveBeenCalled()
  })

  it('列表里模型连接标注模型数，并提供拉取入口', async () => {
    mocks.list.mockResolvedValue([
      {
        id: 'c1',
        tenantId: 'space-alpha',
        name: 'deepseek',
        type: 'LLM',
        authType: 'API_KEY',
        config: { models: ['a', 'b', 'c'] },
        enabled: true,
        hasCredential: true,
        inherited: false,
      },
    ])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('3 个模型')
    expect(wrapper.findAll('button').some((b) => b.text() === '拉取模型')).toBe(true)
  })
})
