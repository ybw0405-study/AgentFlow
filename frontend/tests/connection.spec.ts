import { describe, expect, it } from 'vitest'
import { connectionProblem } from '@/dsl/connection'
import type { FlowEdgeLike, FlowNodeLike } from '@/dsl/flow-convert'
import type { NodeType } from '@/dsl/types'

function node(id: string, nodeType: NodeType): FlowNodeLike {
  return {
    id,
    type: 'af',
    position: { x: 0, y: 0 },
    data: { nodeType, label: id, config: {} },
  }
}

function edge(from: string, to: string): FlowEdgeLike {
  return { id: `${from}->${to}`, source: from, target: to }
}

const graph = [node('start', 'start'), node('a', 'llm'), node('end', 'end')]

describe('落线前即时校验', () => {
  it('正常连线放行', () => {
    expect(connectionProblem(graph, [], { source: 'start', target: 'a' })).toBeNull()
  })

  it('拒绝自连', () => {
    expect(connectionProblem(graph, [], { source: 'a', target: 'a' })).toContain('不能连到自己')
  })

  it('end 不能再往外连', () => {
    expect(connectionProblem(graph, [], { source: 'end', target: 'a' })).toContain('收口')
  })

  it('start 不能作为终点，子图给出对应说明', () => {
    expect(connectionProblem(graph, [], { source: 'a', target: 'start' })).toContain('入口')
    expect(connectionProblem(graph, [], { source: 'a', target: 'start' }, true)).toContain('子图')
  })

  it('重复连线在落线时就提示', () => {
    expect(connectionProblem(graph, [edge('start', 'a')], { source: 'start', target: 'a' })).toContain('已经存在')
  })

  it('端点不在当前画布时提示', () => {
    expect(connectionProblem(graph, [], { source: 'a', target: 'missing' })).toContain('当前画布')
  })
})
