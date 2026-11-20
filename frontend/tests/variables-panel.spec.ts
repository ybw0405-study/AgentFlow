import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { VariableSpec } from '@/dsl/types'
import VariablesPanel from '@/components/canvas/VariablesPanel.vue'

function vars(count: number): Record<string, VariableSpec> {
  return Object.fromEntries(
    Array.from({ length: count }, (_, i) => [`v${i}`, { type: 'string', required: true }]),
  )
}

const expandedOf = (wrapper: ReturnType<typeof mount>) =>
  wrapper.find('.af-vars__head').attributes('aria-expanded')

describe('变量面板折叠', () => {
  it('变量很少时默认展开', () => {
    const wrapper = mount(VariablesPanel, { props: { variables: vars(3) } })
    expect(expandedOf(wrapper)).toBe('true')
    expect(wrapper.find('.af-vars__row').isVisible()).toBe(true)
  })

  it('DSL 异步到达后超过 6 个变量会自动折叠（画布先挂载、数据后到）', async () => {
    const wrapper = mount(VariablesPanel, { props: { variables: {} } })
    expect(expandedOf(wrapper)).toBe('true')
    await wrapper.setProps({ variables: vars(7) })
    expect(expandedOf(wrapper)).toBe('false')
    expect(wrapper.find('.af-vars__list').isVisible()).toBe(false)
  })

  it('用户手动展开后，变量数再变化也不再被自动折叠', async () => {
    const wrapper = mount(VariablesPanel, { props: { variables: {} } })
    await wrapper.setProps({ variables: vars(7) })
    expect(expandedOf(wrapper)).toBe('false')
    await wrapper.find('.af-vars__head').trigger('click')
    expect(expandedOf(wrapper)).toBe('true')
    await wrapper.setProps({ variables: vars(9) })
    expect(expandedOf(wrapper)).toBe('true')
  })
})
