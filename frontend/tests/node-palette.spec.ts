import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NodePalette from '@/components/canvas/NodePalette.vue'

function buttonOf(wrapper: ReturnType<typeof mount>, type: string) {
  const found = wrapper
    .findAll('button')
    .find((b) => b.find('.af-palette__type').text() === type)
  if (!found) throw new Error(`palette 缺少 ${type} 按钮`)
  return found
}

describe('节点面板', () => {
  it('没有 start 时可添加 start', async () => {
    const wrapper = mount(NodePalette, { props: { startTaken: false } })
    const btn = buttonOf(wrapper, 'start')
    expect(btn.attributes('disabled')).toBeUndefined()
    await btn.trigger('click')
    expect(wrapper.emitted('add')).toEqual([['start']])
  })

  it('已有 start 时禁用 start 并说明原因', () => {
    const wrapper = mount(NodePalette, { props: { startTaken: true } })
    const btn = buttonOf(wrapper, 'start')
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.attributes('title')).toContain('只能有一个')
  })

  it('start 被禁用时其它节点不受影响', async () => {
    const wrapper = mount(NodePalette, { props: { startTaken: true } })
    const btn = buttonOf(wrapper, 'llm')
    expect(btn.attributes('disabled')).toBeUndefined()
    await btn.trigger('click')
    expect(wrapper.emitted('add')).toEqual([['llm']])
  })
})
