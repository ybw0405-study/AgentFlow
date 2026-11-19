/** 客户端 DSL 预校验（服务端/engine 仍是权威；这里只做即时反馈）。 */

import type { WorkflowDsl } from './types'

export interface ValidationIssue {
  nodeKey?: string
  field?: string
  message: string
}

const VAR_REF = /\bvars\.([A-Za-z_][A-Za-z0-9_]*)/g
const NODE_REF = /\bnodes\.([a-z][a-zA-Z0-9_-]{0,63})/g
/** 只有这两类节点能出分支边（与服务端 DSL-BRANCH-001 对齐）。 */
const BRANCH_NODE_TYPES = new Set(['condition', 'human_gate'])
/** 子图字段：内部有自己的作用域（迭代变量、局部节点），不参与外层引用检查。 */
const SUBGRAPH_KEYS = new Set(['body', 'branches'])

export function validateDslLocal(dsl: WorkflowDsl): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const keys = new Set<string>()

  for (const n of dsl.nodes) {
    if (keys.has(n.key)) {
      issues.push({ nodeKey: n.key, message: `重复 nodeKey「${n.key}」` })
    }
    keys.add(n.key)
  }

  const starts = dsl.nodes.filter((n) => n.type === 'start')
  if (starts.length !== 1) {
    issues.push({ message: `需要恰好 1 个 start 节点（当前 ${starts.length} 个）` })
  }
  if (!dsl.nodes.some((n) => n.type === 'end')) {
    issues.push({ message: '至少需要 1 个 end 节点' })
  }

  for (const e of dsl.edges) {
    if (!keys.has(e.from)) {
      issues.push({ message: `边引用了不存在的节点「${e.from}」` })
    }
    if (!keys.has(e.to)) {
      issues.push({ message: `边引用了不存在的节点「${e.to}」` })
    }
  }

  // 分支边：只能从分支节点出发，审批节点只认 approved / rejected
  const byKey = new Map(dsl.nodes.map((n) => [n.key, n]))
  for (const e of dsl.edges) {
    if (!e.branch) continue
    const src = byKey.get(e.from)
    if (!src) continue
    if (!BRANCH_NODE_TYPES.has(src.type)) {
      issues.push({
        nodeKey: e.from,
        message: `只有 condition / human_gate 能出分支边，${e.from} 是 ${src.type}`,
      })
    } else if (src.type === 'human_gate' && e.branch !== 'approved' && e.branch !== 'rejected') {
      issues.push({
        nodeKey: e.from,
        message: `human_gate 的分支只能是 approved / rejected，当前是「${e.branch}」`,
      })
    }
  }

  // 环检测（Kahn）
  const inDegree = new Map<string, number>()
  const out = new Map<string, string[]>()
  for (const k of keys) {
    inDegree.set(k, 0)
    out.set(k, [])
  }
  for (const e of dsl.edges) {
    if (keys.has(e.from) && keys.has(e.to)) {
      inDegree.set(e.to, (inDegree.get(e.to) ?? 0) + 1)
      out.get(e.from)?.push(e.to)
    }
  }
  const queue = [...keys].filter((k) => (inDegree.get(k) ?? 0) === 0)
  let visited = 0
  while (queue.length) {
    const cur = queue.pop() as string
    visited++
    for (const nxt of out.get(cur) ?? []) {
      const d = (inDegree.get(nxt) ?? 0) - 1
      inDegree.set(nxt, d)
      if (d === 0) {
        queue.push(nxt)
      }
    }
  }
  if (visited < keys.size) {
    const inCycle = [...keys].filter((k) => (inDegree.get(k) ?? 0) > 0)
    issues.push({ nodeKey: inCycle[0], message: `存在环：${inCycle.join(' → ')}` })
  }

  // 配置必填 + 引用检查
  // 写入型变量（transform.target / loop.collect.into）在运行期由节点创建，下游可直接引用，
  // 不应要求先在工作流变量里声明一遍。
  const definedVars = new Set(Object.keys(dsl.variables ?? {}))
  for (const n of dsl.nodes) {
    for (const v of writtenVars(n)) {
      definedVars.add(v)
    }
  }
  for (const n of dsl.nodes) {
    const cfg = n.config as Record<string, unknown>
    if (n.type === 'llm') {
      if (!cfg.model) issues.push({ nodeKey: n.key, field: 'model', message: 'llm 需要 model' })
      if (!cfg.promptTemplate) {
        issues.push({ nodeKey: n.key, field: 'promptTemplate', message: 'llm 需要 promptTemplate' })
      }
    }
    if (n.type === 'tool') {
      if (!cfg.connectorId) {
        issues.push({ nodeKey: n.key, field: 'connectorId', message: 'tool 需要 connectorId' })
      }
      if (!cfg.operation) {
        issues.push({ nodeKey: n.key, field: 'operation', message: 'tool 需要 operation' })
      }
    }
    if (n.type === 'end') {
      const mapping = cfg.resultMapping as Record<string, string> | undefined
      if (!mapping || Object.keys(mapping).length === 0) {
        issues.push({ nodeKey: n.key, field: 'resultMapping', message: 'end 需要 resultMapping' })
      }
    }
    if (n.type === 'condition') {
      const cases = (cfg.cases as { name?: string; when?: string }[] | undefined) ?? []
      if (!cases.length) {
        issues.push({ nodeKey: n.key, field: 'cases', message: 'condition 至少需要一个 case' })
      }
      const names = new Set<string>()
      cases.forEach((c, i) => {
        if (!c?.name) {
          issues.push({ nodeKey: n.key, field: 'cases', message: `第 ${i + 1} 个 case 缺少 name` })
        } else if (names.has(c.name)) {
          issues.push({ nodeKey: n.key, field: 'cases', message: `case 名重复「${c.name}」` })
        } else {
          names.add(c.name)
        }
        if (!c?.when) {
          issues.push({ nodeKey: n.key, field: 'cases', message: `case「${c?.name ?? i}」缺少 when` })
        }
      })
      if (!cfg.elseBranch) {
        issues.push({ nodeKey: n.key, field: 'elseBranch', message: 'condition 需要 elseBranch' })
      }
      // 出边 branch 必须是某个 case 名或 elseBranch，否则该分支永远走不到
      const allowed = new Set([...names, String(cfg.elseBranch ?? '')])
      for (const e of dsl.edges) {
        if (e.from === n.key && e.branch && !allowed.has(e.branch)) {
          issues.push({
            nodeKey: n.key,
            message: `分支边「${e.branch}」不在 cases / elseBranch 里，永远不会命中`,
          })
        }
      }
    }
    if (n.type === 'transform') {
      const mappings = (cfg.mappings as { target?: string }[] | undefined) ?? []
      if (!mappings.length) {
        issues.push({ nodeKey: n.key, field: 'mappings', message: 'transform 至少需要一条映射' })
      }
      mappings.forEach((m, i) => {
        if (!m?.target?.startsWith('vars.')) {
          issues.push({
            nodeKey: n.key,
            field: 'mappings',
            message: `第 ${i + 1} 条映射的 target 必须以 vars. 开头`,
          })
        }
      })
    }
    if (n.type === 'human_gate') {
      if (!cfg.promptTemplate) {
        issues.push({ nodeKey: n.key, field: 'promptTemplate', message: 'human_gate 需要提示词' })
      }
      const approvers = cfg.approvers as { type?: string; role?: string; userIds?: string[] }
      if (!approvers?.type) {
        issues.push({ nodeKey: n.key, field: 'approvers', message: 'human_gate 需要审批人规则' })
      } else if (approvers.type === 'ROLE' && !approvers.role) {
        issues.push({ nodeKey: n.key, field: 'approvers', message: '按角色审批需要选择角色' })
      } else if (approvers.type === 'USERS' && !(approvers.userIds ?? []).length) {
        issues.push({ nodeKey: n.key, field: 'approvers', message: '按用户审批需要填写用户 id' })
      } else if (approvers.type === 'EXPRESSION') {
        issues.push({ nodeKey: n.key, field: 'approvers', message: '引擎暂不支持 EXPRESSION 审批人' })
      }
    }
    if (n.type === 'subworkflow') {
      if (!cfg.definitionId) {
        issues.push({ nodeKey: n.key, field: 'definitionId', message: 'subworkflow 需要目标工作流' })
      }
      if (cfg.versionPolicy === 'FIXED' && !cfg.versionId) {
        issues.push({ nodeKey: n.key, field: 'versionId', message: '固定版本策略需要 versionId' })
      }
    }
    if (n.type === 'loop') {
      if (!cfg.itemsExpression) {
        issues.push({ nodeKey: n.key, field: 'itemsExpression', message: 'loop 需要 itemsExpression' })
      }
      if (!cfg.body) {
        issues.push({ nodeKey: n.key, field: 'body', message: 'loop 需要 body 子图' })
      }
    }
    if (n.type === 'parallel') {
      const branches = cfg.branches as Record<string, unknown> | undefined
      if (!branches || Object.keys(branches).length < 2) {
        issues.push({ nodeKey: n.key, field: 'branches', message: 'parallel 至少需要两个分支' })
      }
    }
    for (const text of refTexts(cfg, n.type)) {
      for (const m of text.matchAll(VAR_REF)) {
        if (!definedVars.has(m[1])) {
          issues.push({ nodeKey: n.key, message: `引用未声明变量 vars.${m[1]}` })
        }
      }
      for (const m of text.matchAll(NODE_REF)) {
        if (!keys.has(m[1])) {
          issues.push({ nodeKey: n.key, message: `引用不存在的节点 nodes.${m[1]}` })
        }
      }
    }
  }
  return issues
}

/** 节点运行期写入的变量名（transform 的 target、loop 的 collect.into）。 */
function writtenVars(n: WorkflowDsl['nodes'][number]): string[] {
  const cfg = (n.config ?? {}) as Record<string, unknown>
  const out: string[] = []
  if (n.type === 'transform' && Array.isArray(cfg.mappings)) {
    for (const m of cfg.mappings as Record<string, unknown>[]) {
      const target = typeof m?.target === 'string' ? m.target : ''
      if (target.startsWith('vars.')) {
        out.push(target.slice('vars.'.length))
      }
    }
  } else if (n.type === 'loop' && cfg.collect && typeof cfg.collect === 'object') {
    const into = (cfg.collect as Record<string, unknown>).into
    if (typeof into === 'string' && into) {
      out.push(into)
    }
  }
  return out
}

/** 只取「引用型」文本：写入目标（transform.target / loop.collect.into）是定义，不是引用。 */
function refTexts(cfg: Record<string, unknown>, nodeType: string): string[] {
  const copy = JSON.parse(JSON.stringify(cfg ?? {})) as Record<string, unknown>
  if (nodeType === 'transform' && Array.isArray(copy.mappings)) {
    copy.mappings = (copy.mappings as Record<string, unknown>[]).map((m) => ({
      template: m?.template ?? '',
    }))
  } else if (nodeType === 'loop' && copy.collect && typeof copy.collect === 'object') {
    delete (copy.collect as Record<string, unknown>).into
  }
  return collectStrings(copy)
}

function collectStrings(value: unknown): string[] {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.flatMap((v) => collectStrings(v))
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .filter(([k]) => !SUBGRAPH_KEYS.has(k))
      .flatMap(([, v]) => collectStrings(v))
  }
  return []
}
