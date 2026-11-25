import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfigDrawer from '@/components/canvas/ConfigDrawer.vue'
import type { DslNode } from '@/dsl/types'

function llm(config: Record<string, unknown>): DslNode {
  return { key: 'draft', type: 'llm', config }
}

interface Catalog {
  loaded: boolean
  configured: boolean
  error: string | null
  models: string[]
}

function mountDrawer(
  node: DslNode,
  catalog?: Catalog,
  connections?: { id: string; name: string; models: string[]; defaultModel: string }[],
) {
  return mount(ConfigDrawer, {
    props: {
      node,
      issues: [],
      modelCatalog: catalog ?? { loaded: true, configured: true, error: null, models: [] },
      llmConnections: connections ?? [],
    },
  })
}

/** provider 下拉与「模型来源」下拉都存在，断言时只取前者，避免被来源选项干扰。 */
function providerOptions(wrapper: ReturnType<typeof mountDrawer>): (string | undefined)[] {
  const select = wrapper
    .findAll('select')
    .find((s) => s.html().includes('openai-compatible'))
  return select!.findAll('option').map((o) => o.attributes('value'))
}

describe('节点配置抽屉', () => {
  it('LLM 节点提供「从常见模型快速接入」入口', async () => {
    const wrapper = mountDrawer(llm({ provider: 'mock', model: 'mock-large' }))
    const quick = wrapper.findAll('button').find((b) => b.text().includes('从常见模型快速接入'))
    expect(quick).toBeTruthy()
    await quick!.trigger('click')
    expect(wrapper.emitted('pick-model')).toHaveLength(1)
  })

  it('占位模型名时明确告知会回落到服务端默认模型', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'mock-large' }))
    expect(wrapper.text()).toContain('服务端默认模型')
  })

  it('真实模型名时直接展示实际下发值', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'deepseek-chat' }))
    expect(wrapper.text()).toContain('实际下发：deepseek-chat')
    expect(wrapper.text()).not.toContain('服务端默认模型')
  })

  it('provider 下拉只提供真实模型', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'deepseek-chat' }))
    expect(providerOptions(wrapper)).toEqual(['openai-compatible'])
  })

  it('旧版离线节点仍能读出自身取值，方便改回真实模型', () => {
    const wrapper = mountDrawer(llm({ provider: 'mock', model: 'mock-large' }))
    expect(providerOptions(wrapper)).toEqual(['openai-compatible'])
    expect(wrapper.text()).toContain('mock-large')
  })

  it('模型清单渲染为 datalist 候选', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'deepseek-chat' }), {
      loaded: true,
      configured: true,
      error: null,
      models: ['deepseek-chat', 'deepseek-reasoner'],
    })
    const options = wrapper.findAll('datalist option').map((o) => o.attributes('value'))
    expect(options).toEqual(['deepseek-chat', 'deepseek-reasoner'])
    expect(wrapper.text()).toContain('共 2 个可用模型')
  })

  it('服务端未配置端点时提示先配置，而不是显示空列表', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'deepseek-chat' }), {
      loaded: true,
      configured: false,
      error: null,
      models: [],
    })
    expect(wrapper.text()).toContain('未配置 AF_LLM_*')
  })

  it('mcp 节点按 capability 渲染 uri / promptName 表单', () => {
    const resource = mountDrawer({
      key: 'mcp_1',
      type: 'mcp',
      config: { connectorId: 'c1', capability: 'resource', uri: 'test://doc' },
    })
    expect(resource.text()).toContain('资源读取')
    expect(resource.find('input[placeholder="test://doc 或 {{ vars.doc }}"]').exists()).toBe(true)

    const prompt = mountDrawer({
      key: 'mcp_1',
      type: 'mcp',
      config: { connectorId: 'c1', capability: 'prompt', promptName: 'greet' },
    })
    expect(prompt.text()).toContain('promptName')
    expect(prompt.text()).toContain('argumentsMapping')
  })

  it('start 节点同样可以删除（清理多余起点）', () => {
    const wrapper = mountDrawer({ key: 'start_1', type: 'start', config: {} })
    const btn = wrapper.findAll('button').find((b) => b.text().includes('删除节点'))
    expect(btn).toBeTruthy()
  })

  it('没有模型连接时引导去连接器页新建', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'deepseek-chat' }))
    expect(wrapper.text()).toContain('还没有模型连接')
  })

  it('有模型连接时来源下拉列出连接与模型数', () => {
    const wrapper = mountDrawer(llm({ model: 'deepseek-chat' }), undefined, [
      { id: 'c1', name: 'deepseek', models: ['deepseek-chat', 'deepseek-reasoner'], defaultModel: 'deepseek-chat' },
    ])
    const source = wrapper.findAll('select')[0]
    expect(source.text()).toContain('进程默认')
    expect(source.text()).toContain('deepseek（2 个模型）')
  })

  it('选中连接后 model 候选来自该连接清单，且仍可手填', () => {
    const wrapper = mountDrawer(llm({ connectionId: 'c1', model: 'deepseek-chat' }), undefined, [
      { id: 'c1', name: 'deepseek', models: ['deepseek-chat', 'deepseek-reasoner'], defaultModel: 'deepseek-chat' },
    ])
    const options = wrapper.findAll('datalist option').map((o) => o.attributes('value'))
    expect(options).toEqual(['deepseek-chat', 'deepseek-reasoner'])
    // 手填能力必须保留：上游清单常常不全，锁成下拉就没法用别的模型
    const modelInput = wrapper.findAll('input').find((i) => i.attributes('list') === 'af-connection-models')
    expect(modelInput).toBeTruthy()
    expect(wrapper.text()).toContain('端点与密钥由服务端解密注入')
  })

  it('连接还没有模型清单时提示去拉取，并允许直接填', () => {
    const wrapper = mountDrawer(llm({ connectionId: 'c1', model: '' }), undefined, [
      { id: 'c1', name: 'deepseek', models: [], defaultModel: '' },
    ])
    expect(wrapper.text()).toContain('还没有模型清单')
    expect(wrapper.findAll('input').some((i) => i.attributes('list') === 'af-connection-models')).toBe(true)
  })

  it('切到连接时把不在清单里的 model 对齐到默认模型', async () => {
    const wrapper = mountDrawer(llm({ model: 'gpt-4o-mini' }), undefined, [
      { id: 'c1', name: 'deepseek', models: ['deepseek-chat'], defaultModel: 'deepseek-chat' },
    ])
    await wrapper.findAll('select')[0].setValue('c1')
    expect(wrapper.emitted('update')?.at(-1)?.[0]).toMatchObject({
      config: { connectionId: 'c1', model: 'deepseek-chat' },
    })
  })

  it('切回进程默认会清掉 connectionId', async () => {
    const wrapper = mountDrawer(llm({ connectionId: 'c1', model: 'deepseek-chat' }), undefined, [
      { id: 'c1', name: 'deepseek', models: ['deepseek-chat'], defaultModel: 'deepseek-chat' },
    ])
    await wrapper.findAll('select')[0].setValue('')
    const emitted = wrapper.emitted('update')?.at(-1)?.[0] as { config: Record<string, unknown> }
    expect(emitted.config.connectionId).toBeUndefined()
    expect(emitted.config.model).toBe('deepseek-chat')
  })

  it('连接没有模型清单时提供一键拉取入口', async () => {
    const wrapper = mountDrawer(
      llm({ connectionId: 'c1', provider: 'openai-compatible', model: 'deepseek-chat' }),
      undefined,
      [{ id: 'c1', name: 'deepseek', models: [], defaultModel: '' }],
    )
    const btn = wrapper.findAll('button').find((b) => b.text().includes('立即拉取模型'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    expect(wrapper.emitted('probe-models')?.[0]).toEqual(['c1'])
  })

  it('retrieval 节点列出知识库并可多选', async () => {
    const wrapper = mount(ConfigDrawer, {
      props: {
        node: {
          key: 'kb', type: 'retrieval',
          config: { knowledgeBaseIds: ['kb-1'], queryTemplate: '{{ vars.question }}', topK: 5 },
        },
        issues: [],
        knowledgeBases: [
          { id: 'kb-1', name: '售后手册', documentCount: 2 },
          { id: 'kb-2', name: '价格表', documentCount: 1 },
        ],
      },
    })
    expect(wrapper.text()).toContain('售后手册')
    expect(wrapper.text()).toContain('真实检索')
    const boxes = wrapper.findAll('input[type="checkbox"]')
    // 2 个知识库 + 1 个 rerank 精排开关（rerank 排在知识库列表之后）
    expect(boxes).toHaveLength(3)
    expect((boxes[2].element as HTMLInputElement).checked).toBe(false)
    expect((boxes[0].element as HTMLInputElement).checked).toBe(true)
    await boxes[1].setValue(true)
    const emitted = wrapper.emitted('update')?.at(-1)?.[0] as { config: Record<string, unknown> }
    expect(emitted.config.knowledgeBaseIds).toEqual(['kb-1', 'kb-2'])
  })

  it('没有知识库时给出导入入口', async () => {
    const wrapper = mount(ConfigDrawer, {
      props: {
        node: { key: 'kb', type: 'retrieval', config: { knowledgeBaseIds: [], queryTemplate: 'x' } },
        issues: [],
        knowledgeBases: [],
      },
    })
    const btn = wrapper.findAll('button').find((b) => b.text().includes('去导入文档'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    expect(wrapper.emitted('open-knowledge')).toHaveLength(1)
  })
})
