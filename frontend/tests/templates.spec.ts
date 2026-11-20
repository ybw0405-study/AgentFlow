import { describe, expect, it } from 'vitest'
import { WORKFLOW_TEMPLATES } from '@/dsl/templates'
import { validateDslLocal } from '@/dsl/validate'

describe('工作流模板', () => {
  it('每个模板都能通过本地校验，落进来就能发布', () => {
    for (const template of WORKFLOW_TEMPLATES) {
      expect(validateDslLocal(template.dsl), template.name).toEqual([])
    }
  })

  it('每个模板都有唯一 id、start 与 end', () => {
    const ids = WORKFLOW_TEMPLATES.map((t) => t.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const template of WORKFLOW_TEMPLATES) {
      const types = template.dsl.nodes.map((n) => n.type)
      expect(types).toContain('start')
      expect(types).toContain('end')
      const keys = template.dsl.nodes.map((n) => n.key)
      expect(new Set(keys).size).toBe(keys.length)
    }
  })

  it('并行模板的每条分支至少两个节点（与引擎一致）', () => {
    const parallel = WORKFLOW_TEMPLATES.find((t) => t.id === 'parallel')!
    const gather = parallel.dsl.nodes.find((n) => n.type === 'parallel')!
    const branches = gather.config.branches as Record<string, { nodes: unknown[] }>
    for (const branch of Object.values(branches)) {
      expect(branch.nodes.length).toBeGreaterThanOrEqual(2)
    }
  })

  it('模板是独立数据，取用深拷贝不会影响其它模板', () => {
    const before = JSON.stringify(WORKFLOW_TEMPLATES)
    const copy = JSON.parse(before)
    copy[0].dsl.nodes[0].key = 'changed'
    expect(JSON.stringify(WORKFLOW_TEMPLATES)).toBe(before)
  })
})
