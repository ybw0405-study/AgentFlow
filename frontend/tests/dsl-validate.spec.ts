import { describe, expect, it } from 'vitest'
import { validateDslLocal } from '@/dsl/validate'
import type { WorkflowDsl } from '@/dsl/types'

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
})
