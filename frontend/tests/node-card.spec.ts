import { describe, expect, it } from 'vitest'
import {
  categoryColor,
  inlineFields,
  nodeCategory,
  nodeSummary,
  statusColor,
  NODE_TYPE_LABELS,
} from '@/dsl/node-card'
import { NODE_TYPE_METAS } from '@/dsl/types'

describe('节点卡片呈现规则', () => {
  it('所有可添加节点都有中文名', () => {
    for (const meta of NODE_TYPE_METAS) {
      expect(NODE_TYPE_LABELS[meta.type]).toBeTruthy()
    }
  })

  it('控制类 / 边界类 / 执行类各自归位', () => {
    expect(nodeCategory('condition')).toBe('control')
    expect(nodeCategory('parallel')).toBe('control')
    expect(nodeCategory('start')).toBe('boundary')
    expect(nodeCategory('llm')).toBe('exec')
  })

  it('颜色只返回设计令牌，不出现原始色值与绿色', () => {
    for (const c of ['exec', 'control', 'boundary'] as const) {
      const color = categoryColor(c)
      expect(color.startsWith('var(')).toBe(true)
      expect(color).not.toMatch(/green|lime/i)
    }
  })

  it('状态色守三色语义，完成不使用绿色', () => {
    expect(statusColor('SUCCEEDED')).toBe('var(--af-neutral)')
    expect(statusColor('SUSPENDED')).toBe('var(--af-accent)')
    expect(statusColor('FAILED')).toBe('var(--af-danger)')
    expect(statusColor('SKIPPED')).toBe('var(--af-text-faint)')
    expect(statusColor()).toBeNull()
    expect(statusColor('SUCCEEDED')).not.toMatch(/green|lime/i)
  })

  it('llm / tool 的高频字段可内联编辑', () => {
    expect(inlineFields('llm', { model: 'm', promptTemplate: 'p' }).map((f) => f.key))
      .toEqual(['model', 'promptTemplate'])
    expect(inlineFields('tool', { connectorId: 'c', operation: 'o' }).every((f) => !f.readOnly))
      .toBe(true)
  })

  it('llm 模型字段是选择器，旧版离线模型给出重新选择提示', () => {
    const fields = inlineFields('llm', { provider: 'mock', model: 'mock-large' })
    expect(fields[0].kind).toBe('picker')
    expect(fields[0].value).toContain('旧版离线模型')
    expect(nodeSummary('llm', { provider: 'mock', model: 'mock-large' }))
      .toContain('接入真实服务')
    expect(nodeSummary('llm', { provider: 'openai-compatible', model: 'hy4' })).toBe('')
  })

  it('嵌套结构只读，避免内联编辑破坏 cases / mappings', () => {
    const fields = inlineFields('condition', {
      cases: [{ name: 'yes', when: 'True' }],
      elseBranch: 'default',
    })
    expect(fields.find((f) => f.key === 'cases')?.readOnly).toBe(true)
    expect(fields.find((f) => f.key === 'elseBranch')?.readOnly).toBeFalsy()
  })

  it('子图规模与审批人规则在卡片摘要里可见', () => {
    expect(nodeSummary('parallel', { branches: { kb: { nodes: [] }, crm: { nodes: [] } } }))
      .toContain('2 条分支')
    expect(nodeSummary('loop', { body: { nodes: [{}, {}] }, itemVar: 'item' }))
      .toContain('2 节点')
    expect(nodeSummary('human_gate', { approvers: { type: 'EXPRESSION', expression: 'user.platformAdmin == true' } }))
      .toContain('表达式')
  })

  it('retrieval 卡片显示文档集数量、TopK 与真实检索语义', () => {
    const fields = inlineFields('retrieval', { knowledgeBaseIds: ['kb-1', 'kb-2'], topK: 5 })
    expect(fields.find((f) => f.key === 'knowledgeBaseIds')?.value).toContain('2 个知识库')
    expect(fields.find((f) => f.key === 'topK')?.kind).toBe('number')
    expect(nodeSummary('retrieval', { knowledgeBaseIds: ['kb-1'], topK: 5 }))
      .toContain('真实文档检索')
  })
})
