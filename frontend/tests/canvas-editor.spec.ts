import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import CanvasEditorView from '@/views/CanvasEditorView.vue'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  saveDraft: vi.fn(),
  freeze: vi.fn(),
  trigger: vi.fn(),
  push: vi.fn(),
  models: vi.fn(),
  connections: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    workflowApi: {
      ...actual.workflowApi,
      detail: mocks.detail,
      saveDraft: mocks.saveDraft,
      freeze: mocks.freeze,
    },
    executionApi: { ...actual.executionApi, trigger: mocks.trigger },
    llmApi: { ...actual.llmApi, models: mocks.models },
    connectorApi: { ...actual.connectorApi, list: mocks.connections },
  }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'wf-1' } }),
  useRouter: () => ({ push: mocks.push }),
}))

// Vue Flow 依赖真实尺寸与坐标系统，jsdom 下不渲染；这里替身只保留事件契约，
// 让被测的是"选中 → 删除"这条交互逻辑本身。
vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    name: 'VueFlow',
    props: ['nodes', 'edges', 'deleteKeyCode', 'fitViewOnInit'],
    emits: ['connect', 'nodeClick', 'edgeClick', 'paneClick', 'nodesInitialized', 'update:nodes', 'update:edges'],
    template: '<div class="flow-stub"><slot /></div>',
  },
  Handle: { name: 'Handle', template: '<span />' },
  Position: { Left: 'left', Right: 'right' },
}))

vi.mock('@vue-flow/background', () => ({
  Background: { name: 'Background', template: '<div />' },
}))

vi.mock('@vue-flow/controls', () => ({
  Controls: { name: 'Controls', template: '<div />' },
}))

const DSL = {
  dslVersion: '1.0',
  variables: { question: { type: 'string', required: true } },
  nodes: [
    { key: 'start', type: 'start', config: {} },
    {
      key: 'draft',
      type: 'llm',
      config: { provider: 'mock', model: 'mock-large', promptTemplate: '{{ vars.question }}' },
    },
    { key: 'end', type: 'end', config: { resultMapping: { answer: '{{ vars.question }}' } } },
  ],
  edges: [
    { from: 'start', to: 'draft' },
    { from: 'draft', to: 'end' },
  ],
}

async function mountCanvas() {
  mocks.detail.mockResolvedValue({
    definition: {
      seq: 1,
      id: 'wf-1',
      name: 'demo',
      description: null,
      status: 'DRAFT',
      currentVersionId: null,
      draftDsl: JSON.stringify(DSL),
      draftChecksum: null,
      createdBy: 'u-editor',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
    versions: [],
  })
  const wrapper = mount(CanvasEditorView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  await flushPromises()
  return wrapper
}
function flow(wrapper: Awaited<ReturnType<typeof mountCanvas>>) {
  return wrapper.findComponent({ name: 'VueFlow' })
}

describe('画布交互', () => {
  beforeEach(() => {
    mocks.detail.mockReset()
    mocks.saveDraft.mockReset()
    mocks.saveDraft.mockResolvedValue({ ok: true, checksum: 'sum-draft' })
    mocks.freeze.mockReset()
    mocks.freeze.mockResolvedValue({ id: 'v-9', versionNo: 1, dslChecksum: 'sum-draft' })
    mocks.trigger.mockReset()
    mocks.trigger.mockResolvedValue({ executionId: 'ex-1' })
    mocks.connections.mockReset()
    mocks.connections.mockResolvedValue([
      {
        id: 'c-deepseek',
        tenantId: 'space-alpha',
        name: 'deepseek',
        type: 'LLM',
        authType: 'API_KEY',
        config: { models: ['deepseek-chat'], defaultModel: 'deepseek-chat' },
        enabled: true,
        hasCredential: true,
        inherited: false,
      },
      {
        id: 'c-tool',
        tenantId: 'space-alpha',
        name: 'crm',
        type: 'HTTP',
        authType: 'API_KEY',
        config: {},
        enabled: true,
        hasCredential: true,
        inherited: false,
      },
    ])
    mocks.models.mockReset()
    mocks.models.mockResolvedValue({
      configured: true,
      defaultModel: 'deepseek-chat',
      models: ['deepseek-chat', 'deepseek-reasoner'],
      error: null,
    })
  })

  it('点击连线后出现选中浮条，删除按钮可移除该连线', async () => {
    const wrapper = await mountCanvas()
    expect(wrapper.text()).not.toContain('连线')

    flow(wrapper).vm.$emit('edgeClick', { edge: { id: 'draft->end' } })
    await flushPromises()
    expect(wrapper.text()).toContain('连线 draft → end')

    const removeBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(removeBtn).toBeTruthy()
    await removeBtn!.trigger('click')
    await flushPromises()

    const edges = flow(wrapper).props('edges') as { id: string }[]
    expect(edges.map((e) => e.id)).toEqual(['start->draft'])
    expect(wrapper.text()).not.toContain('连线')
  })

  it('选中连线后按 Delete 键删除', async () => {
    const wrapper = await mountCanvas()
    flow(wrapper).vm.$emit('edgeClick', { edge: { id: 'start->draft' } })
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    await flushPromises()

    const edges = flow(wrapper).props('edges') as { id: string }[]
    expect(edges.map((e) => e.id)).toEqual(['draft->end'])
  })

  it('删除节点会级联删掉它的进出连线', async () => {
    const wrapper = await mountCanvas()
    flow(wrapper).vm.$emit('nodeClick', { node: { id: 'draft' } })
    await flushPromises()
    expect(wrapper.text()).toContain('节点 draft')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as { id: string }[]
    const edges = flow(wrapper).props('edges') as { id: string }[]
    expect(nodes.map((n) => n.id)).toEqual(['start', 'end'])
    expect(edges).toHaveLength(0)
  })

  it('在输入框里按 Delete 不会误删节点', async () => {
    const wrapper = await mountCanvas()
    flow(wrapper).vm.$emit('nodeClick', { node: { id: 'draft' } })
    await flushPromises()

    const input = wrapper.find('input')
    input.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', bubbles: true }))
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as { id: string }[]
    expect(nodes.map((n) => n.id)).toEqual(['start', 'draft', 'end'])
  })

  it('已有 start 时节点面板禁用 start', async () => {
    const wrapper = await mountCanvas()
    const startBtn = wrapper
      .find('.af-palette')
      .findAll('button')
      .find((b) => b.find('.af-palette__type').text() === 'start')
    expect(startBtn?.attributes('disabled')).toBeDefined()
  })

  it('新建 llm 节点默认接真实 provider，并带上服务端默认模型', async () => {
    const wrapper = await mountCanvas()
    const llmBtn = wrapper
      .find('.af-palette')
      .findAll('button')
      .find((b) => b.find('.af-palette__type').text() === 'llm')
    await llmBtn!.trigger('click')
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as {
      id: string
      data: { config: { provider?: string; model?: string } }
    }[]
    const created = nodes.find((n) => n.id.startsWith('llm_'))
    expect(created?.data.config.provider).toBe('openai-compatible')
    expect(created?.data.config.model).toBe('deepseek-chat')
  })

  it('已有模型连接时，新建 llm 节点直接挂上它', async () => {
    const wrapper = await mountCanvas()
    const llmBtn = wrapper
      .find('.af-palette')
      .findAll('button')
      .find((b) => b.find('.af-palette__type').text() === 'llm')
    await llmBtn!.trigger('click')
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as {
      id: string
      data: { config: { connectionId?: string; model?: string } }
    }[]
    const created = nodes.find((n) => n.id.startsWith('llm_'))
    // HTTP 类型的连接不该出现在模型来源里，所以只会挂上 LLM 那一条
    expect(created?.data.config.connectionId).toBe('c-deepseek')
  })

  it('连接列表里只有 HTTP 连接时，llm 节点不带 connectionId', async () => {
    mocks.connections.mockResolvedValue([
      {
        id: 'c-tool',
        tenantId: 'space-alpha',
        name: 'crm',
        type: 'HTTP',
        authType: 'API_KEY',
        config: {},
        enabled: true,
        hasCredential: true,
        inherited: false,
      },
    ])
    const wrapper = await mountCanvas()
    const llmBtn = wrapper
      .find('.af-palette')
      .findAll('button')
      .find((b) => b.find('.af-palette__type').text() === 'llm')
    await llmBtn!.trigger('click')
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as {
      id: string
      data: { config: { connectionId?: string } }
    }[]
    const created = nodes.find((n) => n.id.startsWith('llm_'))
    expect(created?.data.config.connectionId).toBeUndefined()
  })

  /** 选中节点后从抽屉快速入口唤起向导；VueFlow 是替身，节点卡片不走这里。 */
  async function openModelHub(wrapper: Awaited<ReturnType<typeof mountCanvas>>) {
    flow(wrapper).vm.$emit('nodeClick', { node: { id: 'draft' } })
    await flushPromises()
    const quick = wrapper
      .findAll('button')
      .find((b) => b.text().includes('从常见模型快速接入'))
    expect(quick).toBeTruthy()
    await quick!.trigger('click')
    await flushPromises()
    return wrapper.findComponent({ name: 'ModelHub' })
  }

  it('抽屉里的快速入口能唤起模型清单', async () => {
    const wrapper = await mountCanvas()
    const hub = await openModelHub(wrapper)
    expect(hub.exists()).toBe(true)
    expect(wrapper.text()).toContain('选择模型')
    expect(wrapper.text()).toContain('混元 Hy4')
  })

  it('连接成功后把 connectionId / model 写回节点并关闭向导', async () => {
    const wrapper = await mountCanvas()
    const hub = await openModelHub(wrapper)
    hub.vm.$emit('connected', {
      ok: true,
      connectorId: 'c-hy4',
      name: '混元 Hy4 连接',
      model: 'hy4',
      models: ['hy4'],
    })
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as {
      id: string
      data: { config: Record<string, unknown> }
    }[]
    const draft = nodes.find((n) => n.id === 'draft')
    expect(draft?.data.config.connectionId).toBe('c-hy4')
    expect(draft?.data.config.model).toBe('hy4')
    expect(draft?.data.config.provider).toBe('openai-compatible')
    expect(wrapper.findComponent({ name: 'ModelHub' }).exists()).toBe(false)
    expect(wrapper.text()).toContain('已接入')
  })

  it('改用进程默认端点会清掉连接引用，仍走真实 provider', async () => {
    const wrapper = await mountCanvas()
    const hub = await openModelHub(wrapper)
    hub.vm.$emit('builtin')
    await flushPromises()

    const nodes = flow(wrapper).props('nodes') as {
      id: string
      data: { config: Record<string, unknown> }
    }[]
    const draft = nodes.find((n) => n.id === 'draft')
    expect(draft?.data.config.connectionId).toBeUndefined()
    expect(draft?.data.config.model).toBe('deepseek-chat')
    expect(draft?.data.config.provider).toBe('openai-compatible')
  })

  it('运行按当前草稿冻结版本并显式传 versionId，不跑旧的已发布版本', async () => {
    const wrapper = await mountCanvas()
    const open = wrapper.findAll('button').find((b) => b.text() === '运行')
    expect(open).toBeTruthy()
    await open!.trigger('click')
    await flushPromises()

    const confirm = wrapper.findAll('button').find((b) => b.text() === '触发执行')
    expect(confirm).toBeTruthy()
    await confirm!.trigger('click')
    await flushPromises()

    expect(mocks.saveDraft).toHaveBeenCalledWith('wf-1', expect.objectContaining({ dslVersion: '1.0' }))
    expect(mocks.freeze).toHaveBeenCalledWith('wf-1')
    expect(mocks.trigger).toHaveBeenCalledWith(
      'wf-1',
      expect.objectContaining({ versionId: 'v-9' }),
    )
  })
})
