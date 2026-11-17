/** 客户端 DSL 预校验（服务端/engine 仍是权威；这里只做即时反馈）。 */

import type { WorkflowDsl } from './types'

export interface ValidationIssue {
  nodeKey?: string
  field?: string
  message: string
}

const VAR_REF = /\bvars\.([A-Za-z_][A-Za-z0-9_]*)/g
const NODE_REF = /\bnodes\.([a-z][a-zA-Z0-9_-]{0,63})/g

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
    for (const text of collectStrings(cfg)) {
      for (const m of text.matchAll(VAR_REF)) {
        if (!(m[1] in dsl.variables)) {
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

function collectStrings(value: unknown): string[] {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.flatMap(collectStrings)
  if (value && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).flatMap(collectStrings)
  }
  return []
}
