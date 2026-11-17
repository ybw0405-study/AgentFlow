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
    expect(newNodeConfig('llm').provider).toBe('mock')
    expect(newNodeConfig('tool').connectorId).toBe('mock.search')
    expect(newNodeConfig('end').resultMapping).toBeTruthy()
  })
})
