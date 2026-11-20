import { describe, expect, it } from 'vitest'
import { dslToFlow, flowToDsl, newNodeConfig } from '@/dsl/flow-convert'
import type { WorkflowDsl } from '@/dsl/types'

const demo: WorkflowDsl = {
  dslVersion: '1.0',
  variables: { question: { type: 'string', required: true, pii: true } },
  defaults: { timeoutMs: 30000, maxParallelism: 4 },
  nodes: [
    { key: 'start', type: 'start', config: {}, position: { x: 10, y: 20 } },
    {
      key: 'ask',
      type: 'llm',
      config: { model: 'mock-large', promptTemplate: '{{ vars.question }}', maxTokens: 64 },
      position: { x: 300, y: 20 },
    },
    {
      key: 'end',
      type: 'end',
      config: { resultMapping: { answer: '{{ nodes.ask.output.text }}' } },
      position: { x: 560, y: 20 },
    },
  ],
  edges: [
    { from: 'start', to: 'ask' },
    { from: 'ask', to: 'end' },
  ],
}

describe('flow-convert', () => {
  it('dslToFlow maps nodes with positions and data', () => {
    const graph = dslToFlow(demo)
    expect(graph.nodes).toHaveLength(3)
    const ask = graph.nodes.find((n) => n.id === 'ask')
    expect(ask?.position).toEqual({ x: 300, y: 20 })
    expect(ask?.data.nodeType).toBe('llm')
    expect(graph.edges.map((e) => e.id)).toEqual(['start->ask', 'ask->end'])
  })

  it('flowToDsl roundtrips structurally', () => {
    const graph = dslToFlow(demo)
    const back = flowToDsl(graph, demo.variables, demo.defaults)
    expect(back.dslVersion).toBe('1.0')
    expect(back.nodes.map((n) => n.key).sort()).toEqual(['ask', 'end', 'start'])
    expect(back.edges).toHaveLength(2)
    const ask = back.nodes.find((n) => n.key === 'ask')
    expect(ask?.config.model).toBe('mock-large')
    expect(ask?.position).toEqual({ x: 300, y: 20 })
  })

  it('newNodeConfig provides type defaults', () => {
    expect(newNodeConfig('tool').connectorId).toBe('')
    expect(newNodeConfig('end').resultMapping).toBeTruthy()
  })

  it('没有模型连接时新建 llm 仍走真实 provider，不会静默产出假文本', () => {
    const cfg = newNodeConfig('llm', 'deepseek-chat')
    expect(cfg.provider).toBe('openai-compatible')
    expect(cfg.model).toBe('deepseek-chat')
    expect(newNodeConfig('llm').model).toBe('deepseek-chat')
  })

  it('已有模型连接时新建 llm 直接挂上连接', () => {
    const cfg = newNodeConfig('llm', '', { id: 'conn-1', defaultModel: 'deepseek-chat' })
    expect(cfg.provider).toBe('openai-compatible')
    expect(cfg.connectionId).toBe('conn-1')
    expect(cfg.model).toBe('deepseek-chat')
  })

  it('retrieval 默认带上第一个知识库，没有知识库时留空由校验提示', () => {
    const cfg = newNodeConfig('retrieval', undefined, null, 'kb-1')
    expect(cfg.knowledgeBaseIds).toEqual(['kb-1'])
    expect(cfg.queryTemplate).toBeTruthy()
    expect(newNodeConfig('retrieval').knowledgeBaseIds).toEqual([])
  })
})
