import { describe, expect, it } from 'vitest'
import { newNodeConfig } from '@/dsl/flow-convert'
import { setEdgeBranch } from '@/dsl/graph-ops'
import type { CanvasEdge } from '@/dsl/graph-ops'
import { validateDslLocal } from '@/dsl/validate'
import type { DslNode, WorkflowDsl } from '@/dsl/types'

function dsl(nodes: DslNode[], edges: WorkflowDsl['edges'] = [], vars: WorkflowDsl['variables'] = {}): WorkflowDsl {
  return { dslVersion: '1.0', variables: vars, nodes, edges }
}

describe('节点默认配置', () => {
  it('新拖出的节点能直接通过本地校验（不制造一落地就报红的节点）', () => {
    for (const type of ['condition', 'transform', 'human_gate', 'loop', 'parallel'] as const) {
      const config = newNodeConfig(type)
      expect(config, type).toBeTruthy()
    }
    const loopBody = newNodeConfig('loop').body as { nodes: DslNode[] }
    expect(loopBody.nodes.length).toBeGreaterThan(0)
    expect(Object.keys(newNodeConfig('parallel').branches as object)).toEqual(['a', 'b'])
  })

  it('condition 默认带一个能命中的 case', () => {
    const cfg = newNodeConfig('condition')
    expect(cfg.cases).toEqual([{ name: 'yes', when: 'True' }])
    expect(cfg.elseBranch).toBe('default')
  })

  it('human_gate 默认按角色审批、超时驳回', () => {
    const cfg = newNodeConfig('human_gate')
    expect(cfg.approvers).toEqual({ type: 'ROLE', role: 'ADMIN' })
    expect((cfg.onTimeout as { action: string }).action).toBe('REJECT')
  })
})

describe('连线分支', () => {
  const edges: CanvasEdge[] = [{ id: 'cond->a', source: 'cond', target: 'a' }]

  it('设置分支时同步改 id，选中态仍指向这条边', () => {
    const next = setEdgeBranch(edges, 'cond->a', 'yes')
    expect(next).not.toBeNull()
    expect(next?.[0].id).toBe('cond->a#yes')
    expect(next?.[0].label).toBe('yes')
  })

  it('清空分支回到无标签状态', () => {
    const withBranch: CanvasEdge[] = [
      { id: 'cond->a#yes', source: 'cond', target: 'a', label: 'yes' },
    ]
    const next = setEdgeBranch(withBranch, 'cond->a#yes', '')
    expect(next?.[0].id).toBe('cond->a')
    expect(next?.[0].label).toBeUndefined()
  })

  it('分支名与已有边冲突时返回 null', () => {
    const dup: CanvasEdge[] = [
      { id: 'cond->a#yes', source: 'cond', target: 'a', label: 'yes' },
      { id: 'cond->b#no', source: 'cond', target: 'b', label: 'no' },
    ]
    expect(setEdgeBranch(dup, 'cond->b#no', 'yes')).toBeNull()
  })
})

describe('本地校验', () => {
  it('condition 的出边分支必须在 cases / elseBranch 内', () => {
    const issues = validateDslLocal(
      dsl(
        [
          { key: 'start', type: 'start', config: {} },
          { key: 'gate', type: 'condition', config: newNodeConfig('condition') },
          { key: 'end', type: 'end', config: { resultMapping: { a: '1' } } },
        ],
        [
          { from: 'start', to: 'gate' },
          { from: 'gate', to: 'end', branch: 'nope' },
        ],
      ),
    )
    expect(issues.some((i) => i.message.includes('永远不会命中'))).toBe(true)
  })

  it('非分支节点出分支边直接报错', () => {
    const issues = validateDslLocal(
      dsl(
        [
          { key: 'start', type: 'start', config: {} },
          { key: 'llm_1', type: 'llm', config: { model: 'm', promptTemplate: 'p' } },
          { key: 'end', type: 'end', config: { resultMapping: { a: '1' } } },
        ],
        [
          { from: 'start', to: 'llm_1' },
          { from: 'llm_1', to: 'end', branch: 'yes' },
        ],
      ),
    )
    expect(issues.some((i) => i.message.includes('只有 condition / human_gate'))).toBe(true)
  })

  it('human_gate 分支只认 approved / rejected', () => {
    const issues = validateDslLocal(
      dsl(
        [
          { key: 'start', type: 'start', config: {} },
          { key: 'gate', type: 'human_gate', config: newNodeConfig('human_gate') },
          { key: 'end', type: 'end', config: { resultMapping: { a: '1' } } },
        ],
        [
          { from: 'start', to: 'gate' },
          { from: 'gate', to: 'end', branch: 'ok' },
        ],
      ),
    )
    expect(issues.some((i) => i.message.includes('approved / rejected'))).toBe(true)
  })

  it('transform 的 target 是定义，不要求先声明变量', () => {
    const issues = validateDslLocal(
      dsl([
        { key: 'start', type: 'start', config: {} },
        {
          key: 'prep',
          type: 'transform',
          config: { mappings: [{ target: 'vars.tmp', template: 'x' }] },
        },
        { key: 'end', type: 'end', config: { resultMapping: { a: 'x' } } },
      ]),
    )
    expect(issues.some((i) => i.nodeKey === 'prep' && i.message.includes('vars.tmp'))).toBe(false)
  })

  it('loop 子图内的迭代变量不按外层作用域报未声明', () => {
    const issues = validateDslLocal(
      dsl([
        { key: 'start', type: 'start', config: {} },
        { key: 'loop_1', type: 'loop', config: newNodeConfig('loop') },
        { key: 'end', type: 'end', config: { resultMapping: { n: '{{ nodes.loop_1.output.count }}' } } },
      ]),
    )
    expect(issues.filter((i) => i.message.includes('vars.item'))).toHaveLength(0)
  })

  it('parallel 少于两个分支时报错', () => {
    const cfg = newNodeConfig('parallel')
    const issues = validateDslLocal(
      dsl([
        { key: 'start', type: 'start', config: {} },
        { key: 'fan', type: 'parallel', config: { ...cfg, branches: { only: { nodes: [], edges: [] } } } },
        { key: 'end', type: 'end', config: { resultMapping: { a: '1' } } },
      ]),
    )
    expect(issues.some((i) => i.message.includes('至少需要两个分支'))).toBe(true)
  })
})

describe('变量写入与引用', () => {
  it('transform 写入的变量在下游可直接引用', () => {
    const issues = validateDslLocal(
      dsl(
        [
          { key: 'start', type: 'start', config: {} },
          {
            key: 'prep',
            type: 'transform',
            config: { mappings: [{ target: 'vars.summary', template: '{{ vars.question }}' }] },
          },
          { key: 'end', type: 'end', config: { resultMapping: { s: '{{ vars.summary }}' } } },
        ],
        [
          { from: 'start', to: 'prep' },
          { from: 'prep', to: 'end' },
        ],
        { question: { type: 'string', required: true } },
      ),
    )
    expect(issues.filter((i) => i.message.includes('vars.summary'))).toHaveLength(0)
  })

  it('loop 的 collect.into 同样视为已定义变量', () => {
    const cfg = { ...newNodeConfig('loop'), collect: { into: 'tags', item: 'value' } }
    const issues = validateDslLocal(
      dsl(
        [
          { key: 'start', type: 'start', config: {} },
          { key: 'loop_1', type: 'loop', config: cfg },
          { key: 'end', type: 'end', config: { resultMapping: { t: '{{ vars.tags }}' } } },
        ],
        [
          { from: 'start', to: 'loop_1' },
          { from: 'loop_1', to: 'end' },
        ],
        { items: { type: 'array', required: true } },
      ),
    )
    expect(issues.filter((i) => i.message.includes('vars.tags'))).toHaveLength(0)
  })

  it('真正没声明的变量仍然报错', () => {
    const issues = validateDslLocal(
      dsl([
        { key: 'start', type: 'start', config: {} },
        { key: 'end', type: 'end', config: { resultMapping: { x: '{{ vars.nope }}' } } },
      ]),
    )
    expect(issues.some((i) => i.message.includes('vars.nope'))).toBe(true)
  })
})
