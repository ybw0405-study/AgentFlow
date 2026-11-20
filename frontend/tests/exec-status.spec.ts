import { describe, expect, it } from 'vitest'
import { aggregateStatus } from '@/dsl/exec-status'
import type { NodeExecutionDto } from '@/types/api'

function row(nodeKey: string, status: string): NodeExecutionDto {
  return {
    seq: 1,
    nodeKey,
    displayOrder: 1,
    status,
    attempt: 1,
    outputDigest: null,
    tokenUsed: 0,
    latencyMs: null,
    retryCount: 0,
    errorCode: null,
    errorMessage: null,
    startedAt: null,
    finishedAt: null,
  }
}

describe('执行图状态聚合', () => {
  it('根层节点有精确记录时直接用精确值', () => {
    expect(aggregateStatus([row('review', 'SUSPENDED')], 'review')).toBe('SUSPENDED')
  })

  it('子节点取最严重状态，失败不会被成功盖住', () => {
    const rows = [
      row('gather#kb#kb_hit', 'SUCCEEDED'),
      row('gather#crm#crm_hit', 'FAILED'),
      row('gather#kb#kb_done', 'SUCCEEDED'),
    ]
    expect(aggregateStatus(rows, 'gather')).toBe('FAILED')
  })

  it('循环多轮也聚合到同一个根层卡片', () => {
    const rows = [row('polish#0#sign', 'SUCCEEDED'), row('polish#1#sign', 'SUCCEEDED')]
    expect(aggregateStatus(rows, 'polish')).toBe('SUCCEEDED')
  })

  it('前缀相似但不属于该子图的节点不误匹配', () => {
    expect(aggregateStatus([row('gather2#x', 'FAILED')], 'gather')).toBeNull()
  })

  it('完全没有记录时返回 null，卡片保持无状态', () => {
    expect(aggregateStatus([], 'start')).toBeNull()
  })
})
