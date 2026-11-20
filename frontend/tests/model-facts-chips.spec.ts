import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ModelFactsChips from '@/components/canvas/ModelFactsChips.vue'

describe('模型能力标签', () => {
  it('推理型模型标出来，并在 maxTokens 偏小时给出可一键采纳的建议', async () => {
    const wrapper = mount(ModelFactsChips, {
      props: { model: 'deepseek-reasoner', currentMaxTokens: 1024 },
    })

    expect(wrapper.text()).toContain('推理型')
    expect(wrapper.text()).toContain('4096')

    await wrapper.find('.af-facts__btn').trigger('click')
    expect(wrapper.emitted('apply-max-tokens')?.[0]).toEqual([4096])
  })

  it('普通对话模型不显示任何标签与建议', () => {
    const wrapper = mount(ModelFactsChips, {
      props: { model: 'deepseek-chat', currentMaxTokens: 1024 },
    })

    expect(wrapper.find('.af-facts').exists()).toBe(false)
    expect(wrapper.find('.af-facts__hint').exists()).toBe(false)
  })

  it('maxTokens 已经够大时不再催', () => {
    const wrapper = mount(ModelFactsChips, {
      props: { model: 'deepseek-reasoner', currentMaxTokens: 8192 },
    })

    expect(wrapper.text()).toContain('推理型')
    expect(wrapper.find('.af-facts__hint').exists()).toBe(false)
  })

  it('上游给的数字直接展示，并标明数据来源是上游', () => {
    const wrapper = mount(ModelFactsChips, {
      props: {
        model: 'acme-large',
        items: [
          {
            id: 'acme-large',
            tags: ['long-context', 'json'],
            contextWindow: 200_000,
            maxOutputTokens: 16_384,
            source: 'upstream',
          },
        ],
      },
    })

    expect(wrapper.text()).toContain('长上下文')
    expect(wrapper.text()).toContain('支持 JSON')
    expect(wrapper.text()).toContain('200k 上下文')
    expect(wrapper.text()).not.toContain('推断')
  })
})
