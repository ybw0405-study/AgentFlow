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

function mountDrawer(node: DslNode, catalog?: Catalog) {
  return mount(ConfigDrawer, {
    props: {
      node,
      issues: [],
      modelCatalog: catalog ?? { loaded: true, configured: true, error: null, models: [] },
    },
  })
}

describe('节点配置抽屉', () => {
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
    const values = wrapper.findAll('option').map((o) => o.attributes('value'))
    expect(values).toEqual(['openai-compatible'])
  })

  it('遗留 mock 节点仍能显示自身取值，便于改回真实模型', () => {
    const wrapper = mountDrawer(llm({ provider: 'mock', model: 'mock-large' }))
    const values = wrapper.findAll('option').map((o) => o.attributes('value'))
    expect(values).toEqual(['mock', 'openai-compatible'])
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
})
