import { describe, expect, it } from 'vitest'
import { initialVariables } from '@/dsl/run-defaults'
import type { VariableSpec } from '@/dsl/types'

describe('运行弹窗 initialVariables 初值', () => {
  it('按声明类型给合法零值，不给布尔/数字塞空串', () => {
    const variables: Record<string, VariableSpec> = {
      ticket: { type: 'string', required: true },
      urgent: { type: 'boolean', required: true },
      retries: { type: 'integer' },
      ratio: { type: 'number' },
      meta: { type: 'object' },
      tags: { type: 'array' },
    }
    expect(initialVariables(variables)).toEqual({
      ticket: '',
      urgent: false,
      retries: 0,
      ratio: 0,
      meta: {},
      tags: [],
    })
  })

  it('声明了 default 时优先用 default', () => {
    const variables: Record<string, VariableSpec> = {
      urgent: { type: 'boolean', default: true },
      ticket: { type: 'string', default: '工单内容' },
    }
    expect(initialVariables(variables)).toEqual({ urgent: true, ticket: '工单内容' })
  })

  it('default 为 null 时退回类型零值', () => {
    expect(initialVariables({ urgent: { type: 'boolean', default: null } })).toEqual({
      urgent: false,
    })
  })

  it('没有变量时给出空对象，仍是合法 JSON', () => {
    expect(initialVariables({})).toEqual({})
    expect(JSON.stringify(initialVariables({}))).toBe('{}')
  })

  it('内置示例的两个必填变量直接触发也不会 EXEC-INPUT-001', () => {
    const variables: Record<string, VariableSpec> = {
      ticket: { type: 'string', required: true, pii: true },
      urgent: { type: 'boolean', required: true },
    }
    const result = initialVariables(variables)
    expect(typeof result.ticket).toBe('string')
    expect(typeof result.urgent).toBe('boolean')
  })
})
