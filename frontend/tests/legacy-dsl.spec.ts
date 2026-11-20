import { describe, expect, it } from 'vitest'
import { bindConnectionToLlmNodes, inspectLegacyDsl } from '@/dsl/legacy-dsl'
import type { DslNode, WorkflowDsl } from '@/dsl/types'

function graph(nodes: DslNode[]): WorkflowDsl {
  return {
    dslVersion: '1.0',
    variables: { question: { type: 'string' } },
    nodes,
    edges: [],
  }
}

const offlineLlm: DslNode = {
  key: 'ask',
  type: 'llm',
  config: { provider: 'mock', model: 'mock-large', promptTemplate: '{{ vars.question }}' },
}
const legacyTool: DslNode = {
  key: 'hit',
  type: 'tool',
  config: { connectorId: 'mock.search', operation: 'search' },
}

describe('旧版离线配置判定', () => {
  it('全是离线节点时给出明确提示', () => {
    const state = inspectLegacyDsl(graph([offlineLlm, legacyTool]))
    expect(state.offlineLlm).toBe(1)
    expect(state.legacyTool).toBe(1)
    expect(state.blocked).toBe(true)
    expect(state.summary).toContain('离线 provider 已移除')
  })

  it('挂了模型连接就不再需要提示', () => {
    const real: DslNode = {
      key: 'ask',
      type: 'llm',
      config: { connectionId: 'conn-1', provider: 'openai-compatible', model: 'deepseek-chat' },
    }
    const state = inspectLegacyDsl(graph([real, legacyTool]))
    expect(state.realLlm).toBe(1)
    expect(state.blocked).toBe(false)
    expect(state.summary).toBeNull()
  })

  it('有文档检索节点就不再算无法运行', () => {
    const state = inspectLegacyDsl(graph([
      offlineLlm,
      { key: 'kb', type: 'retrieval', config: { knowledgeBaseIds: ['kb-1'] } },
    ]))
    expect(state.retrieval).toBe(1)
    expect(state.blocked).toBe(false)
  })

  it('子图内的离线节点也计入，不漏报', () => {
    const loop: DslNode = {
      key: 'looper',
      type: 'loop',
      config: {
        body: { nodes: [{ ...offlineLlm, key: 'inner' }], edges: [] },
        branches: {
          a: { nodes: [{ ...legacyTool, key: 'a_hit' }], edges: [] },
          b: { nodes: [{ key: 'b_end', type: 'transform', config: {} }], edges: [] },
        },
      },
    }
    const state = inspectLegacyDsl(graph([loop]))
    expect(state.offlineLlm).toBe(1)
    expect(state.legacyTool).toBe(1)
    expect(state.blocked).toBe(true)
  })

  it('空图不误报', () => {
    expect(inspectLegacyDsl(graph([])).blocked).toBe(false)
  })

  it('已有连接时把模板/示例里的 llm 整层改写，子图也不漏', () => {
    const loop: DslNode = {
      key: 'looper',
      type: 'loop',
      config: { body: { nodes: [{ ...offlineLlm, key: 'inner' }], edges: [] } },
    }
    const dsl = graph(JSON.parse(JSON.stringify([offlineLlm, loop, legacyTool])) as DslNode[])
    const count = bindConnectionToLlmNodes(dsl, { id: 'conn-1', defaultModel: 'deepseek-chat' })
    expect(count).toBe(2)
    expect((dsl.nodes[0].config as Record<string, unknown>).connectionId).toBe('conn-1')
    const body = (dsl.nodes[1].config as Record<string, unknown>).body as { nodes: DslNode[] }
    expect((body.nodes[0].config as Record<string, unknown>).model).toBe('deepseek-chat')
    expect(inspectLegacyDsl(dsl).blocked).toBe(false)
    // tool 节点保持原样，不被误改
    expect((dsl.nodes[2].config as Record<string, unknown>).connectorId).toBe('mock.search')
  })

  it('没有默认模型时不改写，避免把占位模型名写进真实连接', () => {
    const dsl = graph(JSON.parse(JSON.stringify([offlineLlm])) as DslNode[])
    expect(bindConnectionToLlmNodes(dsl, { id: 'conn-1', defaultModel: '' })).toBe(0)
    expect(dsl.nodes[0].config.provider).toBe('mock')
  })
})
