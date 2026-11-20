import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ExecutionGraph from '@/components/execution/ExecutionGraph.vue'
import type { WorkflowDsl } from '@/dsl/types'
import type { NodeExecutionDto } from '@/types/api'

const FlowStub = defineComponent({
  name: 'VueFlow',
  props: {
    nodes: { type: Array, default: () => [] },
    edges: { type: Array, default: () => [] },
  },
  template: '<div class="flow-stub" />',
})

function dsl(): WorkflowDsl {
  return {
    dslVersion: '1.0',
    variables: {},
    nodes: [
      { key: 'start', type: 'start', config: {} },
      { key: 'gather', type: 'parallel', config: { branches: {} } },
      { key: 'end', type: 'end', config: {} },
    ],
    edges: [{ from: 'start', to: 'gather' }, { from: 'gather', to: 'end' }],
  } as WorkflowDsl
}

function row(nodeKey: string, status: string): NodeExecutionDto {
  return {
    seq: 1,
    nodeKey,
    displayOrder: 1,
    status,
    attempt: 1,
    outputDigest: null,
    tokenUsed: 0,
    latencyMs: null,
    retryCount: 0,
    errorCode: null,
    errorMessage: null,
    startedAt: null,
    finishedAt: null,
  }
}

function mountGraph(nodes: NodeExecutionDto[]) {
  return mount(ExecutionGraph, {
    props: { dsl: dsl(), nodes, selectedKey: null },
    global: {
      stubs: {
        VueFlow: FlowStub,
        Background: true,
        Controls: true,
        WorkflowNode: true,
      },
    },
  })
}

describe('执行详情图视图', () => {
  it('按冻结版本 DSL 生成根层节点与连线', () => {
    const wrapper = mountGraph([])
    const flow = wrapper.findComponent(FlowStub)
    expect(flow.exists()).toBe(true)
    expect((flow.props('nodes') as Array<{ id: string }>).map((n) => n.id))
      .toEqual(['start', 'gather', 'end'])
    expect(flow.props('edges')).toHaveLength(2)
  })

  it('节点卡片带只读标记与聚合后的状态', () => {
    const wrapper = mountGraph([
      row('start', 'SUCCEEDED'),
      row('gather#kb#kb_hit', 'FAILED'),
      row('end', 'SUCCEEDED'),
    ])
    const nodes = wrapper.findComponent(FlowStub).props('nodes') as Array<{
      id: string
      data: Record<string, unknown>
    }>
    const gather = nodes.find((n) => n.id === 'gather')!
    expect(gather.data.readOnly).toBe(true)
    expect(gather.data.status).toBe('FAILED')
  })

  it('图例说明状态色语义', () => {
    const wrapper = mountGraph([])
    expect(wrapper.text()).toContain('点节点看输入 / 输出')
    expect(wrapper.text()).toContain('危险=失败')
  })
})
