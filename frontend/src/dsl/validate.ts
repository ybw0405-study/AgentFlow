/** 客户端 DSL 预校验（服务端/engine 仍是权威；这里只做即时反馈）。
 *
 * 子图（loop.body / parallel.branches.*）也要能在画布上编辑，所以校验必须下钻：
 * 同一套规则跑每一层，问题位置用 `loop_1.body#body_end` 这种「子图路径 + 节点 key」
 * 定位，UI 上既不和外层同名节点混淆，也能一眼看出该去哪一层修。
 */

import { approverExpressionError } from './approver-expr'
import { readSubgraph, subgraphTitle } from './subgraph'
import type { DslEdge, DslNode, WorkflowDsl } from './types'

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
/** 嵌套子图的校验深度上限：坏数据不应该把界面拖死。 */
const MAX_SUBGRAPH_DEPTH = 4

interface GraphScope {
  /** 子图定位（如 loop_1.body）；空串表示根层。 */
  prefix: string
  /** 当前层可见的变量名（含外层声明与隐式迭代变量）。 */
  vars: Set<string>
  /** 当前层可见的节点 key（含外层），用于 nodes.* 引用。 */
  nodeKeys: Set<string>
  /** 根层要求恰好一个 start；子图层不允许 start。 */
  root: boolean
}

export function validateDslLocal(dsl: WorkflowDsl): ValidationIssue[] {
  const scope: GraphScope = {
    prefix: '',
    vars: new Set(Object.keys(dsl.variables ?? {})),
    nodeKeys: new Set(),
    root: true,
  }
  return [
    ...validateGraph(dsl.nodes, dsl.edges, scope),
    ...validateNested(dsl.nodes, dsl.edges, scope, 0),
  ]
}

function validateGraph(nodes: DslNode[], edges: DslEdge[], scope: GraphScope): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const at = (key: string | undefined, message: string, field?: string): void => {
    const nodeKey = key ? (scope.prefix ? `${scope.prefix}#${key}` : key) : scope.prefix || undefined
    issues.push({ ...(nodeKey ? { nodeKey } : {}), ...(field ? { field } : {}), message })
  }

  const keys = new Set<string>()
  for (const n of nodes) {
    if (keys.has(n.key)) at(n.key, `重复 nodeKey「${n.key}」`)
    keys.add(n.key)
  }

  const starts = nodes.filter((n) => n.type === 'start')
  if (scope.root) {
    if (starts.length !== 1) at(undefined, `需要恰好 1 个 start 节点（当前 ${starts.length} 个）`)
  } else if (starts.length) {
    // 子图由上层节点驱动进入，start 在这里既没有入边语义也会被引擎拒绝
    at(starts[0].key, '子图内不能有 start 节点：进入子图就是它的入口')
  }
  if (scope.root && !nodes.some((n) => n.type === 'end')) {
    // 只有整图需要 end 收口：子图的出口由上层节点（loop / parallel）决定，
    // 引擎也不要求在 body / branches 里放 end。
    at(undefined, '至少需要 1 个 end 节点')
  }

  for (const e of edges) {
    if (!keys.has(e.from)) at(undefined, `边引用了不存在的节点「${e.from}」`)
    if (!keys.has(e.to)) at(undefined, `边引用了不存在的节点「${e.to}」`)
  }

  // 分支边：只能从分支节点出发，审批节点只认 approved / rejected
  const byKey = new Map(nodes.map((n) => [n.key, n]))
  for (const e of edges) {
    if (!e.branch) continue
    const src = byKey.get(e.from)
    if (!src) continue
    if (!BRANCH_NODE_TYPES.has(src.type)) {
      at(e.from, `只有 condition / human_gate 能出分支边，${e.from} 是 ${src.type}`)
    } else if (src.type === 'human_gate' && e.branch !== 'approved' && e.branch !== 'rejected') {
      at(e.from, `human_gate 的分支只能是 approved / rejected，当前是「${e.branch}」`)
    }
  }

  // 环检测（Kahn）
  const inDegree = new Map<string, number>()
  const out = new Map<string, string[]>()
  for (const k of keys) {
    inDegree.set(k, 0)
    out.set(k, [])
  }
  for (const e of edges) {
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
    at(inCycle[0], `存在环：${inCycle.join(' → ')}`)
  }

  // 配置必填 + 引用检查
  // 写入型变量（transform.target / loop.collect.into）在运行期由节点创建，下游可直接引用，
  // 不应要求先在工作流变量里声明一遍。
  const definedVars = visibleVars(nodes, scope.vars)
  const visibleKeys = new Set([...scope.nodeKeys, ...keys])
  for (const n of nodes) {
    const cfg = n.config as Record<string, unknown>
    if (n.type === 'llm') {
      if (!cfg.model) at(n.key, 'llm 需要 model', 'model')
      if (!cfg.promptTemplate) at(n.key, 'llm 需要 promptTemplate', 'promptTemplate')
    }
    if (n.type === 'tool') {
      if (!cfg.connectorId) at(n.key, 'tool 需要 connectorId', 'connectorId')
      if (!cfg.operation) at(n.key, 'tool 需要 operation', 'operation')
    }
    if (n.type === 'retrieval') {
      const ids = cfg.knowledgeBaseIds
      if (!Array.isArray(ids) || !ids.length) {
        at(n.key, 'retrieval 需要至少选择一个知识库（先在「知识库」页导入文档）', 'knowledgeBaseIds')
      }
      if (!cfg.queryTemplate) at(n.key, 'retrieval 需要 queryTemplate', 'queryTemplate')
    }
    if (n.type === 'end') {
      const mapping = cfg.resultMapping as Record<string, string> | undefined
      if (!mapping || Object.keys(mapping).length === 0) {
        at(n.key, 'end 需要 resultMapping', 'resultMapping')
      }
    }
    if (n.type === 'condition') {
      const cases = (cfg.cases as { name?: string; when?: string }[] | undefined) ?? []
      if (!cases.length) at(n.key, 'condition 至少需要一个 case', 'cases')
      const names = new Set<string>()
      cases.forEach((c, i) => {
        if (!c?.name) {
          at(n.key, `第 ${i + 1} 个 case 缺少 name`, 'cases')
        } else if (names.has(c.name)) {
          at(n.key, `case 名重复「${c.name}」`, 'cases')
        } else {
          names.add(c.name)
        }
        if (!c?.when) at(n.key, `case「${c?.name ?? i}」缺少 when`, 'cases')
      })
      if (!cfg.elseBranch) at(n.key, 'condition 需要 elseBranch', 'elseBranch')
      // 出边 branch 必须是某个 case 名或 elseBranch，否则该分支永远走不到
      const allowed = new Set([...names, String(cfg.elseBranch ?? '')])
      for (const e of edges) {
        if (e.from === n.key && e.branch && !allowed.has(e.branch)) {
          at(n.key, `分支边「${e.branch}」不在 cases / elseBranch 里，永远不会命中`)
        }
      }
    }
    if (n.type === 'transform') {
      const mappings = (cfg.mappings as { target?: string }[] | undefined) ?? []
      if (!mappings.length) at(n.key, 'transform 至少需要一条映射', 'mappings')
      mappings.forEach((m, i) => {
        if (!m?.target?.startsWith('vars.')) {
          at(n.key, `第 ${i + 1} 条映射的 target 必须以 vars. 开头`, 'mappings')
        }
      })
    }
    if (n.type === 'human_gate') {
      if (!cfg.promptTemplate) at(n.key, 'human_gate 需要提示词', 'promptTemplate')
      const approvers = cfg.approvers as
        | { type?: string; role?: string; userIds?: string[]; expression?: string }
        | undefined
      if (!approvers?.type) {
        at(n.key, 'human_gate 需要审批人规则', 'approvers')
      } else if (approvers.type === 'ROLE' && !approvers.role) {
        at(n.key, '按角色审批需要选择角色', 'approvers')
      } else if (approvers.type === 'USERS' && !(approvers.userIds ?? []).length) {
        at(n.key, '按用户审批需要填写用户 id', 'approvers')
      } else if (approvers.type === 'EXPRESSION') {
        const problem = approverExpressionError(approvers.expression ?? '')
        if (problem) at(n.key, `审批人表达式：${problem}`, 'approvers')
      }
    }
    if (n.type === 'subworkflow') {
      if (!cfg.definitionId) at(n.key, 'subworkflow 需要目标工作流', 'definitionId')
      if (cfg.versionPolicy === 'FIXED' && !cfg.versionId) {
        at(n.key, '固定版本策略需要 versionId', 'versionId')
      }
    }
    if (n.type === 'loop') {
      if (!cfg.itemsExpression) at(n.key, 'loop 需要 itemsExpression', 'itemsExpression')
      if (!cfg.body) at(n.key, 'loop 需要 body 子图', 'body')
    }
    if (n.type === 'parallel') {
      const branches = cfg.branches as Record<string, unknown> | undefined
      if (!branches || Object.keys(branches).length < 2) {
        at(n.key, 'parallel 至少需要两个分支', 'branches')
      }
    }
    for (const text of refTexts(cfg, n.type)) {
      for (const m of text.matchAll(VAR_REF)) {
        if (!definedVars.has(m[1])) at(n.key, `引用未声明变量 vars.${m[1]}`)
      }
      for (const m of text.matchAll(NODE_REF)) {
        if (!visibleKeys.has(m[1])) at(n.key, `引用不存在的节点 nodes.${m[1]}`)
      }
    }
  }
  return issues
}

/** 下钻所有子图：每层级联自己的可见作用域，问题位置带子图前缀。 */
function validateNested(
  nodes: DslNode[],
  edges: DslEdge[],
  scope: GraphScope,
  depth: number,
): ValidationIssue[] {
  if (depth >= MAX_SUBGRAPH_DEPTH) return []
  const issues: ValidationIssue[] = []
  const keyOf = (k: string): string => (scope.prefix ? `${scope.prefix}#${k}` : k)
  const dslLike: WorkflowDsl = {
    dslVersion: '1.0',
    variables: {},
    nodes,
    edges,
  }
  /** 子图可见：外层作用域 + 本层节点写入的变量 + 子图隐式变量。 */
  const baseVars = visibleVars(nodes, scope.vars)
  const outerKeys = new Set([...scope.nodeKeys, ...nodes.map((n) => n.key)])

  for (const node of nodes) {
    const cfg = node.config as Record<string, unknown>
    if (node.type === 'loop') {
      const ref = { nodeKey: node.key }
      const graph = readSubgraph(dslLike, ref)
      if (!graph) continue
      if (graph.nodes.length < 2) {
        // 引擎运行期按 WorkflowDsl 解析子图（nodes 至少 2 个）：单节点子图发布能过，
        // 跑起来必炸在分支上，所以这里就当错误拦住。
        issues.push({
          nodeKey: keyOf(node.key),
          message: '子图至少需要 2 个节点：只有 1 个节点的子图无法执行',
        })
      }
      const implicit = [cfg.itemVar, cfg.indexVar].filter(
        (v): v is string => typeof v === 'string' && v.length > 0,
      )
      const childScope: GraphScope = {
        prefix: qualify(scope.prefix, subgraphTitle(dslLike, ref)),
        vars: new Set([...baseVars, ...implicit]),
        nodeKeys: outerKeys,
        root: false,
      }
      issues.push(...validateGraph(graph.nodes, graph.edges, childScope))
      issues.push(...validateNested(graph.nodes, graph.edges, childScope, depth + 1))
      continue
    }
    if (node.type !== 'parallel') continue
    const branches = (cfg.branches ?? {}) as Record<string, unknown>
    for (const branch of Object.keys(branches)) {
      const ref = { nodeKey: node.key, branch }
      const graph = readSubgraph(dslLike, ref)
      if (!graph) continue
      if (graph.nodes.length < 2) {
        issues.push({
          nodeKey: keyOf(node.key),
          message: `分支「${branch}」至少需要 2 个节点：只有 1 个节点的子图无法执行`,
        })
      }
      const childScope: GraphScope = {
        prefix: qualify(scope.prefix, subgraphTitle(dslLike, ref)),
        vars: baseVars,
        nodeKeys: outerKeys,
        root: false,
      }
      issues.push(...validateGraph(graph.nodes, graph.edges, childScope))
      issues.push(...validateNested(graph.nodes, graph.edges, childScope, depth + 1))
    }
  }
  return issues
}

/** 本层重复 key 由 validateGraph 统一报，这里只提供子图路径前缀的拼接。 */
function qualify(prefix: string, title: string): string {
  return prefix ? `${prefix}.${title}` : title
}

/** 当前层可见变量：外层声明 + 本层节点的写入型变量。 */
function visibleVars(nodes: DslNode[], base: Iterable<string>): Set<string> {
  const out = new Set(base)
  for (const n of nodes) {
    for (const v of writtenVars(n)) out.add(v)
  }
  return out
}

/** 节点运行期写入的变量名（transform 的 target、loop 的 collect.into）。 */
function writtenVars(n: DslNode): string[] {
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
