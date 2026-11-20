import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NodeSearch from '@/components/canvas/NodeSearch.vue'

function mountSearch(overrides: Record<string, unknown> = {}) {
  return mount(NodeSearch, {
    props: { inSubgraph: false, hasStart: false, anchorId: null, ...overrides },
  })
}

describe('节点搜索快速落位', () => {
  it('中文名可直接过滤', async () => {
    const wrapper = mountSearch()
    await wrapper.find('input').setValue('审批')
    expect(wrapper.text()).toContain('人工审批')
    expect(wrapper.text()).not.toContain('循环')
  })

  it('Enter 落位并把 connect 标记传给父级', async () => {
    const wrapper = mountSearch({ anchorId: 'a' })
    await wrapper.find('input').setValue('审批')
    await wrapper.find('input').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('add')?.[0]).toEqual(['human_gate', true])
  })

  it('没有选中节点时不自动连线', async () => {
    const wrapper = mountSearch()
    await wrapper.find('input').setValue('并行')
    await wrapper.find('input').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('add')?.[0]).toEqual(['parallel', false])
  })

  it('整图已有 start 时不再提供 start', async () => {
    const wrapper = mountSearch({ hasStart: true })
    await wrapper.find('input').setValue('start')
    expect(wrapper.text()).toContain('没有匹配的节点类型')
  })

  it('子图内也不提供 start', async () => {
    const wrapper = mountSearch({ inSubgraph: true })
    await wrapper.find('input').setValue('起点')
    expect(wrapper.text()).toContain('没有匹配的节点类型')
  })

  it('Esc 关闭', async () => {
    const wrapper = mountSearch()
    await wrapper.find('input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
