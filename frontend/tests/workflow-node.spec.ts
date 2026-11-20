import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkflowNode from '@/components/canvas/WorkflowNode.vue'

function mountNode(overrides: Record<string, unknown> = {}) {
  return mount(WorkflowNode, {
    props: {
      id: 'a',
      data: {
        nodeType: 'llm',
        label: '分诊',
        config: { model: 'mock-llm-large', promptTemplate: '{{ vars.ticket }}' },
        ...overrides,
      },
    },
    global: { stubs: { Handle: true } },
  })
}

describe('节点卡片内联编辑', () => {
  it('渲染标题、类型与内联字段；模型是选择器而不是裸文本框', () => {
    const wrapper = mountNode()
    expect(wrapper.text()).toContain('分诊')
    // 旧草稿里的离线模型名不再显示成占位符，而是说明性的中文名
    expect(wrapper.find('.af-node__picker').text()).toContain('旧版离线模型')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('{{ vars.ticket }}')
    expect(wrapper.findAll('input')).toHaveLength(1)
  })

  it('旧版离线模型给出下一步提示，避免看起来像没配好', () => {
    const wrapper = mountNode()
    expect(wrapper.text()).toContain('点击「模型」接入真实服务')
  })

  it('预设模型显示中文名而不是一串 id', () => {
    const wrapper = mountNode({
      config: { model: 'hy4', promptTemplate: '{{ vars.ticket }}', provider: 'openai-compatible' },
    })
    expect(wrapper.find('.af-node__picker').text()).toContain('混元 Hy4')
  })

  it('点击模型选择器抛出 pick-model', async () => {
    const wrapper = mountNode()
    await wrapper.find('.af-node__picker').trigger('click')
    expect(wrapper.emitted('pick-model')).toHaveLength(1)
  })

  it('改字段就地把 patch 抛给父级', async () => {
    const wrapper = mountNode()
    await wrapper.find('input').setValue('请回答：{{ vars.ticket }}')
    expect(wrapper.emitted('update-config')?.[0]).toEqual([{ promptTemplate: '请回答：{{ vars.ticket }}' }])
  })

  it('数字字段转成 number', async () => {
    const wrapper = mountNode({
      nodeType: 'loop',
      config: { itemsExpression: '[]', maxIterations: 100 },
    })
    const inputs = wrapper.findAll('input')
    await inputs[1].setValue('7')
    expect(wrapper.emitted('update-config')?.[0]).toEqual([{ maxIterations: 7 }])
  })

  it('执行态只读：字段不退化成输入框，按钮改为查看 I/O', () => {
    const wrapper = mountNode({ readOnly: true, status: 'SUCCEEDED', outputDigest: 'abc' })
    expect(wrapper.findAll('input')).toHaveLength(0)
    expect(wrapper.text()).toContain('查看输入 / 输出')
    expect(wrapper.find('.af-node__dot').exists()).toBe(true)
    expect(wrapper.text()).toContain('输出 abc')
  })

  it('进行中/挂起的节点在图上有醒目标记', () => {
    const wrapper = mountNode({ readOnly: true, status: 'SUSPENDED' })
    expect(wrapper.find('.af-node').classes()).toContain('af-node--live')
    expect(mountNode({ readOnly: true, status: 'SUCCEEDED' }).find('.af-node').classes())
      .not.toContain('af-node--live')
  })

  it('失败原因就地展示', () => {
    const wrapper = mountNode({
      readOnly: true,
      status: 'FAILED',
      errorCode: 'EXEC-INPUT-001',
      errorMessage: '变量类型不符',
    })
    expect(wrapper.find('.af-node__error').text()).toContain('变量类型不符')
  })

  it('展开完整配置抛出 expand', async () => {
    const wrapper = mountNode()
    const button = wrapper.findAll('button').find((b) => b.text() === '展开完整配置')
    await button!.trigger('click')
    expect(wrapper.emitted('expand')).toHaveLength(1)
  })

  it('卡片上提供试跑入口，起止节点与循环不给', async () => {
    const wrapper = mountNode()
    const preview = wrapper.findAll('button').find((b) => b.text() === '试跑')
    await preview!.trigger('click')
    expect(wrapper.emitted('preview')).toHaveLength(1)

    expect(mountNode({ nodeType: 'start' }).findAll('button').some((b) => b.text() === '试跑')).toBe(false)
    expect(mountNode({ nodeType: 'loop' }).findAll('button').some((b) => b.text() === '试跑')).toBe(false)
  })
})
