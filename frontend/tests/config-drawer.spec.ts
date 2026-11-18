import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfigDrawer from '@/components/canvas/ConfigDrawer.vue'
import type { DslNode } from '@/dsl/types'

function llm(config: Record<string, unknown>): DslNode {
  return { key: 'draft', type: 'llm', config }
}

function mountDrawer(node: DslNode) {
  return mount(ConfigDrawer, { props: { node, issues: [] } })
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
    expect(wrapper.text()).toContain('不会发起网络请求')
  })

  it('start 节点同样可以删除（清理多余起点）', () => {
    const wrapper = mountDrawer({ key: 'start_1', type: 'start', config: {} })
    const btn = wrapper.findAll('button').find((b) => b.text().includes('删除节点'))
    expect(btn).toBeTruthy()
  })
})
