import { describe, expect, it } from 'vitest'
import { addEdge, markSelectedEdge, removeEdge, removeNode } from '@/dsl/graph-ops'
import type { CanvasEdge } from '@/dsl/graph-ops'
import type { FlowNodeLike } from '@/dsl/flow-convert'

const nodes: FlowNodeLike[] = [
  { id: 'start', type: 'af', position: { x: 0, y: 0 }, data: { nodeType: 'start', label: 'start', config: {} } },
  { id: 'draft', type: 'af', position: { x: 200, y: 0 }, data: { nodeType: 'llm', label: 'draft', config: {} } },
  { id: 'end', type: 'af', position: { x: 400, y: 0 }, data: { nodeType: 'end', label: 'end', config: {} } },
]

const edges: CanvasEdge[] = [
  { id: 'start->draft', source: 'start', target: 'draft' },
  { id: 'draft->end', source: 'draft', target: 'end' },
]

describe('graph-ops', () => {
  it('addEdge 用与 dslToFlow 相同的 id 规则', () => {
    const next = addEdge([], 'start', 'draft')
    expect(next).toEqual([{ id: 'start->draft', source: 'start', target: 'draft' }])
  })

  it('addEdge 拒绝重复连线，避免同 id 覆盖', () => {
    expect(addEdge(edges, 'start', 'draft')).toBeNull()
  })

  it('removeEdge 只删指定连线', () => {
    expect(removeEdge(edges, 'draft->end').map((e) => e.id)).toEqual(['start->draft'])
  })

  it('removeNode 级联删除进出边，不留悬空 edge', () => {
    const next = removeNode(nodes, edges, 'draft')
    expect(next.nodes.map((n) => n.id)).toEqual(['start', 'end'])
    expect(next.edges).toHaveLength(0)
  })

  it('removeNode 保留无关节点的连线', () => {
    const next = removeNode(nodes, edges, 'end')
    expect(next.edges.map((e) => e.id)).toEqual(['start->draft'])
  })

  it('markSelectedEdge 是单选语义', () => {
    const once = markSelectedEdge(edges, 'draft->end')
    expect(once.map((e) => e.selected)).toEqual([false, true])
    const cleared = markSelectedEdge(once, null)
    expect(cleared.every((e) => e.selected === false)).toBe(true)
  })
})
