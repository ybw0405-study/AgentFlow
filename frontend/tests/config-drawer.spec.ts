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

  it('mock provider 说明离线语义', () => {
    const wrapper = mountDrawer(llm({ provider: 'mock', model: 'mock-large' }))
    expect(wrapper.text()).toContain('不发起网络请求')
  })

  it('provider 下拉不再提供 mock 选项', () => {
    const wrapper = mountDrawer(llm({ provider: 'openai-compatible', model: 'deepseek-chat' }))
    expect(providerOptions(wrapper)).toEqual(['openai-compatible'])
  })

  it('遗留 mock 节点仍能显示自身取值，便于改回真实模型', () => {
    const wrapper = mountDrawer(llm({ provider: 'mock', model: 'mock-large' }))
    expect(providerOptions(wrapper)).toEqual(['mock', 'openai-compatible'])
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
})
