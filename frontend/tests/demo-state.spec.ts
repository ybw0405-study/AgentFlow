import { describe, expect, it } from 'vitest'
import { bindConnectionToLlmNodes, inspectDemo } from '@/dsl/demo-state'
import type { DslNode, WorkflowDsl } from '@/dsl/types'

function graph(nodes: DslNode[]): WorkflowDsl {
  return {
    dslVersion: '1.0',
    variables: { question: { type: 'string' } },
    nodes,
    edges: [],
  }
}

const demoLlm: DslNode = {
  key: 'ask',
  type: 'llm',
  config: { provider: 'mock', model: 'mock-large', promptTemplate: '{{ vars.question }}' },
}
const mockTool: DslNode = {
  key: 'hit',
  type: 'tool',
  config: { connectorId: 'mock.search', operation: 'search' },
}

describe('演示态判定', () => {
  it('内置示例全是 mock 时给出明确提示', () => {
    const state = inspectDemo(graph([demoLlm, mockTool]))
    expect(state.demoLlm).toBe(1)
    expect(state.mockTool).toBe(1)
    expect(state.demoOnly).toBe(true)
    expect(state.summary).toContain('不会调用真实大模型')
  })

  it('挂了模型连接就不再算演示运行', () => {
    const real: DslNode = {
      key: 'ask',
      type: 'llm',
      config: { connectionId: 'conn-1', provider: 'openai-compatible', model: 'deepseek-chat' },
    }
    const state = inspectDemo(graph([real, mockTool]))
    expect(state.realLlm).toBe(1)
    expect(state.demoOnly).toBe(false)
    expect(state.summary).toBeNull()
  })

  it('有文档检索节点就不再算纯演示', () => {
    const state = inspectDemo(graph([
      demoLlm,
      { key: 'kb', type: 'retrieval', config: { knowledgeBaseIds: ['kb-1'] } },
    ]))
    expect(state.retrieval).toBe(1)
    expect(state.demoOnly).toBe(false)
  })

  it('子图内的 mock 节点也计入，不漏报', () => {
    const loop: DslNode = {
      key: 'looper',
      type: 'loop',
      config: {
        body: { nodes: [{ ...demoLlm, key: 'inner' }], edges: [] },
        branches: {
          a: { nodes: [{ ...mockTool, key: 'a_hit' }], edges: [] },
          b: { nodes: [{ key: 'b_end', type: 'transform', config: {} }], edges: [] },
        },
      },
    }
    const state = inspectDemo(graph([loop]))
    expect(state.demoLlm).toBe(1)
    expect(state.mockTool).toBe(1)
    expect(state.demoOnly).toBe(true)
  })

  it('空图不误报演示态', () => {
    expect(inspectDemo(graph([])).demoOnly).toBe(false)
  })

  it('已有连接时把模板/示例里的 llm 整层改写，子图也不漏', () => {
    const loop: DslNode = {
      key: 'looper',
      type: 'loop',
      config: { body: { nodes: [{ ...demoLlm, key: 'inner' }], edges: [] } },
    }
    const dsl = graph(JSON.parse(JSON.stringify([demoLlm, loop, mockTool])) as DslNode[])
    const count = bindConnectionToLlmNodes(dsl, { id: 'conn-1', defaultModel: 'deepseek-chat' })
    expect(count).toBe(2)
    expect((dsl.nodes[0].config as Record<string, unknown>).connectionId).toBe('conn-1')
    const body = (dsl.nodes[1].config as Record<string, unknown>).body as { nodes: DslNode[] }
    expect((body.nodes[0].config as Record<string, unknown>).model).toBe('deepseek-chat')
    expect(inspectDemo(dsl).demoOnly).toBe(false)
    // tool 节点保持原样，不被误改
    expect((dsl.nodes[2].config as Record<string, unknown>).connectorId).toBe('mock.search')
  })

  it('没有默认模型时不改写，避免把占位模型名写进真实连接', () => {
    const dsl = graph(JSON.parse(JSON.stringify([demoLlm])) as DslNode[])
    expect(bindConnectionToLlmNodes(dsl, { id: 'conn-1', defaultModel: '' })).toBe(0)
    expect(dsl.nodes[0].config.provider).toBe('mock')
  })
})
