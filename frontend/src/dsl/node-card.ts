import type { NodeType } from './types'
import { displayModelName, isBuiltinDemoModel } from './model-presets'

/** 节点卡片的呈现规则集中在这里：编排画布与执行态画布共用同一套口径。
 *  颜色一律返回设计令牌，不出现原始色值（design-redlines 守卫）。 */

export type NodeCategory = 'exec' | 'control' | 'boundary'

const CONTROL_TYPES = new Set(['condition', 'loop', 'parallel'])
const BOUNDARY_TYPES = new Set(['start', 'end', 'transform'])

export function nodeCategory(type: string): NodeCategory {
  if (CONTROL_TYPES.has(type)) return 'control'
  if (BOUNDARY_TYPES.has(type)) return 'boundary'
  return 'exec'
}

export function categoryColor(category: NodeCategory): string {
  if (category === 'control') return 'var(--af-node-control)'
  if (category === 'boundary') return 'var(--af-neutral)'
  return 'var(--af-accent)'
}

/** 状态色严格守三色语义：完成=中性、进行/挂起=强调、失败=危险、跳过=faint。 */
export function statusColor(status?: string | null): string | null {
  switch (status) {
    case 'SUCCEEDED':
      return 'var(--af-neutral)'
    case 'FAILED':
    case 'TIMED_OUT':
    case 'CANCELLED':
      return 'var(--af-danger)'
    case 'RUNNING':
    case 'SUSPENDED':
    case 'PENDING':
      return 'var(--af-accent)'
    case 'SKIPPED':
      return 'var(--af-text-faint)'
    default:
      return null
  }
}

export interface InlineField {
  key: string
  label: string
  value: string
  kind: 'text' | 'number' | 'select' | 'picker'
  options?: readonly string[]
  /** 嵌套结构只展示摘要，避免内联编辑破坏 cases / mappings 等数组结构。 */
  readOnly?: boolean
}

function text(value: unknown): string {
  return value === undefined || value === null ? '' : String(value)
}

function count(value: unknown): number {
  if (Array.isArray(value)) return value.length
  if (value && typeof value === 'object') return Object.keys(value as object).length
  return 0
}

/** 每类节点挑 1~2 个最常改的顶层字段做内联编辑；复杂结构仍交给完整配置抽屉。 */
export function inlineFields(type: string, config: Record<string, unknown>): InlineField[] {
  switch (type) {
    case 'llm':
      return [
        // 模型不让人对着文本框盲填 id：点开是预设清单 + 一次填 Key 就能连上的向导。
        { key: 'model', label: '模型', value: displayModelName(config), kind: 'picker' },
        { key: 'promptTemplate', label: '提示词', value: text(config.promptTemplate), kind: 'text' },
      ]
    case 'tool':
      return [
        { key: 'connectorId', label: '连接器', value: text(config.connectorId), kind: 'text' },
        { key: 'operation', label: '操作', value: text(config.operation), kind: 'text' },
      ]
    case 'retrieval':
      return [
        {
          key: 'knowledgeBaseIds',
          label: '文档集',
          value: `已选 ${count(config.knowledgeBaseIds)} 个知识库`,
          kind: 'text',
          readOnly: true,
        },
        { key: 'topK', label: 'TopK', value: text(config.topK), kind: 'number' },
      ]
    case 'condition':
      return [
        { key: 'elseBranch', label: '默认分支', value: text(config.elseBranch), kind: 'text' },
        {
          key: 'cases',
          label: '分支条件',
          value: (Array.isArray(config.cases) ? config.cases : [])
            .map((c) => `${text((c as Record<string, unknown>).name)}: ${text((c as Record<string, unknown>).when)}`)
            .join(' / '),
          kind: 'text',
          readOnly: true,
        },
      ]
    case 'transform':
      return [{
        key: 'mappings',
        label: '写回变量',
        value: (Array.isArray(config.mappings) ? config.mappings : [])
          .map((m) => text((m as Record<string, unknown>).target))
          .filter(Boolean)
          .join(' / '),
        kind: 'text',
        readOnly: true,
      }]
    case 'human_gate':
      return [
        { key: 'promptTemplate', label: '审批提示', value: text(config.promptTemplate), kind: 'text' },
        { key: 'timeoutMs', label: '超时(ms)', value: text(config.timeoutMs), kind: 'number' },
      ]
    case 'subworkflow':
      return [
        { key: 'definitionId', label: '子流程 ID', value: text(config.definitionId), kind: 'text' },
        {
          key: 'versionPolicy',
          label: '版本策略',
          value: text(config.versionPolicy),
          kind: 'select',
          options: ['LATEST_PUBLISHED', 'PINNED'],
        },
      ]
    case 'loop':
      return [
        { key: 'itemsExpression', label: '遍历表达式', value: text(config.itemsExpression), kind: 'text' },
        { key: 'maxIterations', label: '最大轮次', value: text(config.maxIterations), kind: 'number' },
      ]
    case 'parallel':
      return [
        { key: 'join', label: '汇聚', value: text(config.join), kind: 'select', options: ['all', 'any', 'n_of_m'] },
        { key: 'maxConcurrency', label: '并发上限', value: text(config.maxConcurrency), kind: 'number' },
      ]
    case 'end':
      return [{
        key: 'resultMapping',
        label: '结果映射',
        value: Object.keys((config.resultMapping as Record<string, unknown>) ?? {}).join(' / '),
        kind: 'text',
        readOnly: true,
      }]
    default:
      return []
  }
}

/** 标题栏下的一行结构摘要，让 loop / parallel / human_gate 的内部信息在图上看得见。 */
export function nodeSummary(type: string, config: Record<string, unknown>): string {
  switch (type) {
    case 'llm':
      // 历史草稿里若仍是演示模型，卡片给出下一步，避免看起来像「没配好」。
      return isBuiltinDemoModel(config) || !text(config.model)
        ? '点击「模型」可接入真实服务'
        : ''
    case 'retrieval':
      return `${count(config.knowledgeBaseIds)} 个知识库 · 真实文档检索 · TopK ${text(config.topK) || '5'}`
    case 'condition':
      return `${count(config.cases)} 个条件分支`
    case 'loop': {
      const body = config.body as { nodes?: unknown[] } | undefined
      return `子图 ${count(body?.nodes)} 节点 · 变量 ${text(config.itemVar) || 'item'}`
    }
    case 'parallel': {
      const branches = config.branches as Record<string, { nodes?: unknown[] }> | undefined
      const names = branches ? Object.keys(branches) : []
      return `${names.length} 条分支：${names.join(' / ')}`
    }
    case 'human_gate': {
      const approvers = config.approvers as Record<string, unknown> | undefined
      const rule = approvers?.type === 'EXPRESSION'
        ? `表达式 ${text(approvers.expression)}`
        : `${text(approvers?.type)} ${text(approvers?.role ?? approvers?.users)}`
      return `审批人 ${rule.trim()}`
    }
    case 'start':
      return '入口：校验并合并初始变量'
    case 'end':
      return '结果映射输出'
    case 'transform':
      return `${count(config.mappings)} 条写回规则`
    case 'subworkflow':
      return `版本 ${text(config.versionPolicy) || 'LATEST_PUBLISHED'}`
    default:
      return ''
  }
}

export const NODE_TYPE_LABELS: Record<NodeType, string> = {
  start: '起点',
  llm: '模型调用',
  tool: '工具调用',
  condition: '条件分支',
  transform: '变量写回',
  subworkflow: '子流程',
  loop: '循环',
  parallel: '并行',
  human_gate: '人工审批',
  end: '收口',
  retrieval: '检索',
}
