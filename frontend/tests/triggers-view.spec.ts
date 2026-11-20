import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import { ApiError } from '@/api/http'
import TriggersView from '@/views/TriggersView.vue'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  list: vi.fn(),
  create: vi.fn(),
  patch: vi.fn(),
  rotate: vi.fn(),
  remove: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    workflowApi: { ...actual.workflowApi, detail: mocks.detail },
    triggerApi: {
      list: mocks.list,
      create: mocks.create,
      patch: mocks.patch,
      rotate: mocks.rotate,
      remove: mocks.remove,
    },
  }
})

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'wf-1' } }) }))

const DRAFT = JSON.stringify({
  dslVersion: '1.0',
  variables: { question: { type: 'string', required: true } },
  nodes: [
    { key: 'start', type: 'start', config: {} },
    { key: 'end', type: 'end', config: { resultMapping: { a: '{{ vars.question }}' } } },
  ],
  edges: [{ from: 'start', to: 'end' }],
})

function detail(published = true) {
  return {
    definition: {
      seq: 1, id: 'wf-1', tenantId: 't', name: '售后问答', description: null,
      status: 'PUBLISHED', currentVersionId: published ? 'v-1' : null, draftDsl: DRAFT,
      draftChecksum: 'c', createdBy: 'u', createdAt: null, updatedAt: null,
    },
    versions: [],
  }
}

const WEBHOOK = {
  id: 'tr-1', type: 'WEBHOOK' as const, token: 'tok-abc', secret: 'sec-123',
  cron: null, timezone: 'Asia/Shanghai', enabled: true, variables: {},
  versionId: null, lastFiredAt: null, createdAt: '2026-01-01T00:00:00Z',
}

async function mountView() {
  const wrapper = mount(TriggersView)
  await flushPromises()
  return wrapper
}

describe('触发页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.detail.mockResolvedValue(detail())
    mocks.list.mockResolvedValue([])
    mocks.create.mockResolvedValue(WEBHOOK)
    mocks.patch.mockResolvedValue(WEBHOOK)
    mocks.rotate.mockResolvedValue(WEBHOOK)
    mocks.remove.mockResolvedValue({ ok: true })
  })

  it('没有 webhook 时给出创建入口', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('创建 Webhook')
    expect(wrapper.text()).toContain('创建定时触发')
  })

  it('已创建时展示可直接复制的入口地址', async () => {
    mocks.list.mockResolvedValue([WEBHOOK])
    const wrapper = await mountView()
    const url = (wrapper.find('input[readonly]').element as HTMLInputElement).value
    expect(url).toContain('/api/v1/hooks/tok-abc')
    expect(wrapper.text()).toContain('轮换令牌')
  })

  it('创建 webhook 调用后端并刷新列表', async () => {
    const wrapper = await mountView()
    const create = wrapper.findAll('button').find((b) => b.text() === '创建 Webhook')
    await create!.trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith('wf-1', { type: 'WEBHOOK' })
    expect(mocks.list).toHaveBeenCalledTimes(2)
  })

  it('未发布时提示触发会失败', async () => {
    mocks.detail.mockResolvedValue(detail(false))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('还没有发布版本')
  })

  it('停用开关把 enabled=false 发给后端', async () => {
    mocks.list.mockResolvedValue([WEBHOOK])
    const wrapper = await mountView()
    const checkbox = wrapper.find('input[type="checkbox"]')
    await checkbox.setValue(false)
    await flushPromises()
    expect(mocks.patch).toHaveBeenCalledWith('tr-1', { enabled: false })
  })

  it('cron 填错时把后端原因显示出来', async () => {
    mocks.create.mockRejectedValue(new ApiError({
      status: 409,
      title: 'Conflict',
      detail: 'invalid cron expression: 0 9 * * 8',
      code: 'WF-STATE-001',
      traceId: 'trace-9',
    } as never))
    const wrapper = await mountView()
    const create = wrapper.findAll('button').find((b) => b.text() === '创建定时触发')
    await create!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前状态不允许此操作')
    expect(wrapper.text()).toContain('invalid cron expression: 0 9 * * 8')
  })
})
