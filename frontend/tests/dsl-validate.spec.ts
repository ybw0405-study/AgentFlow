import { describe, expect, it } from 'vitest'
import { MAX_SUBGRAPH_DEPTH, validateDslLocal } from '@/dsl/validate'
import type { DslNode, WorkflowDsl } from '@/dsl/types'

function base(): WorkflowDsl {
  return {
    dslVersion: '1.0',
    variables: { question: { type: 'string' } },
    nodes: [
      { key: 'start', type: 'start', config: {} },
      { key: 'end', type: 'end', config: { resultMapping: { a: '{{ vars.question }}' } } },
    ],
    edges: [{ from: 'start', to: 'end' }],
  }
}

/** 构造 levels 层嵌套 loop，用于验证子图深度契约。
 *
 * 每层 body 都放「下一层 loop + 一个 end 收口节点」凑够 2 节点，config 不含
 * 未声明引用，这样唯一的失败原因只可能是子图深度本身。
 */
function nestedLoops(levels: number, leafTemplate = 'ok'): WorkflowDsl {
  const subgraph = (
    level: number,
  ): { nodes: DslNode[]; edges: { from: string; to: string }[] } => {
    if (level >= levels) {
      return {
        nodes: [
          {
            key: `leaf_${level}_prep`,
            type: 'transform',
            config: { mappings: [{ target: 'vars.tmp', template: leafTemplate }] },
          },
          {
            key: `leaf_${level}_end`,
            type: 'end',
            config: { resultMapping: { v: '1' } },
          },
        ],
        edges: [{ from: `leaf_${level}_prep`, to: `leaf_${level}_end` }],
      }
    }
    const child = `loop_${level + 1}`
    return {
      nodes: [
        {
          key: child,
          type: 'loop',
          config: {
            itemsExpression: 'vars.tags',
            itemVar: 'item',
            indexVar: 'index',
            maxIterations: 10,
            body: subgraph(level + 1),
          },
        },
        { key: `tail_${level}`, type: 'end', config: { resultMapping: { v: '1' } } },
      ],
      edges: [{ from: child, to: `tail_${level}` }],
    }
  }
  return {
    dslVersion: '1.0',
    variables: { tags: { type: 'array', required: true } },
    nodes: [
      { key: 'start', type: 'start', config: {} },
      {
        key: 'loop_1',
        type: 'loop',
        config: {
          itemsExpression: 'vars.tags',
          itemVar: 'item',
          indexVar: 'index',
          maxIterations: 10,
          body: subgraph(1),
        },
      },
      { key: 'end', type: 'end', config: { resultMapping: { n: '1' } } },
    ],
    edges: [{ from: 'start', to: 'loop_1' }, { from: 'loop_1', to: 'end' }],
  }
}

describe('dsl local validate', () => {
  it('accepts a valid minimal graph', () => {
    expect(validateDslLocal(base())).toEqual([])
  })

  it('detects duplicate keys', () => {
    const dsl = base()
    dsl.nodes.push({ key: 'start', type: 'start', config: {} })
    expect(validateDslLocal(dsl).some((i) => i.message.includes('重复'))).toBe(true)
  })

  it('detects cycles', () => {
    const dsl = base()
    dsl.edges.push({ from: 'end', to: 'start' })
    expect(validateDslLocal(dsl).some((i) => i.message.includes('环'))).toBe(true)
  })

  it('detects undefined variable references', () => {
    const dsl = base()
    dsl.nodes[1].config = { resultMapping: { a: '{{ vars.ghost }}' } }
    expect(validateDslLocal(dsl).some((i) => i.message.includes('vars.ghost'))).toBe(true)
  })

  it('detects missing required config', () => {
    const dsl = base()
    dsl.nodes.splice(1, 0, { key: 't1', type: 'tool', config: {} })
    dsl.edges.push({ from: 'start', to: 't1' }, { from: 't1', to: 'end' })
    const messages = validateDslLocal(dsl).map((i) => i.message)
    expect(messages.some((m) => m.includes('connectorId'))).toBe(true)
    expect(messages.some((m) => m.includes('operation'))).toBe(true)
  })

  it('retrieval 必须选择知识库并给出可操作提示', () => {
    const dsl = base()
    dsl.nodes.splice(1, 0, {
      key: 'kb', type: 'retrieval', config: { queryTemplate: '{{ vars.question }}' },
    })
    dsl.edges = [{ from: 'start', to: 'kb' }, { from: 'kb', to: 'end' }]
    const messages = validateDslLocal(dsl).map((i) => i.message)
    expect(messages.some((m) => m.includes('知识库'))).toBe(true)
  })

  it('retrieval 选了知识库且模板齐全时通过校验', () => {
    const dsl = base()
    dsl.nodes.splice(1, 0, {
      key: 'kb', type: 'retrieval',
      config: {
        knowledgeBaseIds: ['kb-1'], queryTemplate: '{{ vars.question }}',
        topK: 5, minScore: 0,
      },
    })
    dsl.edges = [{ from: 'start', to: 'kb' }, { from: 'kb', to: 'end' }]
    expect(validateDslLocal(dsl)).toEqual([])
  })

  describe('子图嵌套深度（与画布下钻、引擎发布校验同一口径）', () => {
    it(`恰好 ${MAX_SUBGRAPH_DEPTH} 层通过`, () => {
      expect(validateDslLocal(nestedLoops(MAX_SUBGRAPH_DEPTH))).toEqual([])
    })

    it('到达上限仍逐层校验：最内层未声明引用会被报出', () => {
      const issues = validateDslLocal(nestedLoops(MAX_SUBGRAPH_DEPTH, '{{ vars.ghost }}'))
      expect(issues.some((i) => i.message.includes('vars.ghost'))).toBe(true)
    })

    it(`第 ${MAX_SUBGRAPH_DEPTH + 1} 层明确报错而不是静默跳过`, () => {
      const issues = validateDslLocal(nestedLoops(MAX_SUBGRAPH_DEPTH + 1))
      const depth = issues.filter((i) => i.message.includes('子图最多嵌套'))
      expect(depth.length).toBeGreaterThan(0)
      expect(depth.every((i) => i.message.includes(`${MAX_SUBGRAPH_DEPTH} 层`))).toBe(true)
      expect(depth.some((i) => i.nodeKey?.endsWith('#loop_5'))).toBe(true)
    })
  })
})
