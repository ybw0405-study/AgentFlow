import { describe, expect, it } from 'vitest'
import { autoLayout } from '@/dsl/auto-layout'
import type { FlowEdgeLike, FlowNodeLike } from '@/dsl/flow-convert'
import type { NodeType } from '@/dsl/types'

function node(id: string, y = 0): FlowNodeLike {
  return {
    id,
    type: 'af',
    position: { x: 0, y },
    data: { nodeType: 'llm' as NodeType, label: id, config: {} },
  }
}

function edge(from: string, to: string): FlowEdgeLike {
  return { id: `${from}->${to}`, source: from, target: to }
}

const xOf = (nodes: FlowNodeLike[], id: string) =>
  nodes.find((n) => n.id === id)!.position.x

describe('自动分层布局', () => {
  it('线性链路逐层右移', () => {
    const out = autoLayout(
      [node('a'), node('b'), node('c')],
      [edge('a', 'b'), edge('b', 'c')],
    )
    expect(xOf(out, 'a')).toBeLessThan(xOf(out, 'b'))
    expect(xOf(out, 'b')).toBeLessThan(xOf(out, 'c'))
  })

  it('并行分支同层、纵向错开', () => {
    const out = autoLayout(
      [node('a'), node('b'), node('c')],
      [edge('a', 'b'), edge('a', 'c')],
    )
    expect(xOf(out, 'b')).toBe(xOf(out, 'c'))
    const b = out.find((n) => n.id === 'b')!
    const c = out.find((n) => n.id === 'c')!
    expect(b.position.y).not.toBe(c.position.y)
  })

  it('有环也能收敛，不丢节点', () => {
    const out = autoLayout([node('a'), node('b')], [edge('a', 'b'), edge('b', 'a')])
    expect(out).toHaveLength(2)
    expect(out.every((n) => Number.isFinite(n.position.x))).toBe(true)
  })

  it('孤立节点排到最后一层的右侧', () => {
    const out = autoLayout([node('a'), node('b'), node('solo')], [edge('a', 'b')])
    expect(xOf(out, 'solo')).toBeGreaterThan(xOf(out, 'b'))
  })

  it('长链折行，不会把卡片一路排到极右', () => {
    const chain = Array.from({ length: 10 }, (_, i) => node(`n${i}`))
    const links = Array.from({ length: 9 }, (_, i) => edge(`n${i}`, `n${i + 1}`))
    const out = autoLayout(chain, links)
    const xs = out.map((n) => n.position.x)
    expect(Math.max(...xs)).toBeLessThanOrEqual(60 + 3 * 280)
    expect(new Set(out.map((n) => n.position.y)).size).toBeGreaterThan(1)
  })

  it('空图原样返回，不改 key 与 config', () => {
    expect(autoLayout([], [])).toEqual([])
    const original = [node('a')]
    const out = autoLayout(original, [])
    expect(out[0].id).toBe('a')
    expect(out[0].data.config).toEqual({})
  })
})
