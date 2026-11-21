import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  APPROVER_ROLES,
  APPROVER_TYPES,
  EXECUTION_STATUSES,
  LLM_PROVIDERS,
  MISFIRE_POLICY_VALUES,
  NODE_TYPES,
  OUTPUT_MODES,
  PARALLEL_JOIN_POLICIES,
  SUBWORKFLOW_VERSION_POLICIES,
  TIMEOUT_ACTIONS,
  VARIABLE_TYPES,
} from '@/dsl/contract-enums'
import { NODE_TYPE_METAS } from '@/dsl/types'

/**
 * CT-1 防漂移门禁：前端所有 DSL / 跨端枚举收敛到单一 contract-enums.ts，
 * 且每个数组都要跟权威源（引擎 Pydantic Literal / MySQL ENUM / Java enum）对账。
 * 同时扫描源码，禁止再出现手写的枚举字面量副本。
 *
 * Vite 下 import.meta.url 是 http scheme，不能用 fileURLToPath；按 cwd 探测仓库根。
 */
const root = [process.cwd(), resolve(process.cwd(), '..')].find((candidate) =>
  existsSync(resolve(candidate, 'engine-api/app/dsl/models.py')),
)
if (root === undefined) {
  throw new Error('找不到 engine-api/app/dsl/models.py，无法做枚举契约对账')
}
const read = (relativePath: string): string =>
  readFileSync(resolve(root, relativePath), 'utf8')

const MODELS = 'engine-api/app/dsl/models.py'

/** 从 models.py 的 `field: Literal[...]` 抽取值；可限定在某个 class 体内避免同名字段撞车。 */
function engineEnum(field: string, className?: string): string[] {
  let src = read(MODELS)
  if (className) {
    const start = src.indexOf(`class ${className}`)
    expect(start, `models.py 缺少 class ${className}`).toBeGreaterThanOrEqual(0)
    const rest = src.slice(start)
    const next = rest.indexOf('\nclass ', 1)
    src = next >= 0 ? rest.slice(0, next) : rest
  }
  const match = new RegExp(`${field}: Literal\\[([^\\]]+)\\]`).exec(src)
  expect(match, `models.py 缺少 ${field} 的 Literal 定义`).not.toBeNull()
  return [...match![1].matchAll(/"([^"]+)"/g)].map((item) => item[1])
}

/** 节点 type 是判别联合，散落在各 *Node 类的单行 Literal 里。 */
function engineNodeTypes(): string[] {
  const src = read(MODELS)
  const start = src.indexOf('class NodeBase')
  const end = src.indexOf('AnyNode = Annotated[', start)
  expect(start).toBeGreaterThanOrEqual(0)
  expect(end).toBeGreaterThan(start)
  return [...src.slice(start, end).matchAll(/type: Literal\["([^"]+)"\]/g)].map((m) => m[1])
}

/** 执行状态权威源：MySQL workflow_execution.status 的最终 ENUM（V006 加过 SUSPENDED）。 */
function engineExecutionStatuses(): string[] {
  const src = read('db/mysql/migration/V006__gate_eval.sql')
  const match = /MODIFY\s+status\s+ENUM\(([^)]+)\)/i.exec(src)
  expect(match, 'V006 缺少 status ENUM 定义').not.toBeNull()
  return [...match![1].matchAll(/'([^']+)'/g)].map((m) => m[1])
}

/** misfire 策略权威源：Java MisfirePolicy 的枚举常量（去掉 javadoc 里的干扰词）。 */
function engineMisfirePolicies(): string[] {
  const src = read(
    'workflow-service/src/main/java/com/agentflow/workflow/trigger/MisfirePolicy.java',
  )
  const marker = 'public enum MisfirePolicy {'
  const decl = src.indexOf(marker)
  expect(decl).toBeGreaterThanOrEqual(0)
  const rest = src.slice(decl + marker.length)
  const body = rest
    .slice(0, rest.indexOf(';'))
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '')
  return [...body.matchAll(/\b([A-Z][A-Z_]+)\b/g)].map((m) => m[1])
}

describe('DSL 枚举与引擎模型对齐（CT-1 单一事实源）', () => {
  it('subworkflow 版本策略与 SubworkflowConfig.version_policy 完全一致', () => {
    expect([...SUBWORKFLOW_VERSION_POLICIES]).toEqual(engineEnum('version_policy'))
  })

  it('parallel 汇聚策略与 ParallelConfig.join 完全一致', () => {
    expect([...PARALLEL_JOIN_POLICIES]).toEqual(engineEnum('join'))
  })

  it('output_mode 与 LlmConfig.output_mode 完全一致', () => {
    expect([...OUTPUT_MODES]).toEqual(engineEnum('output_mode'))
  })

  it('LLM provider 与 LlmConfig.provider 完全一致', () => {
    expect([...LLM_PROVIDERS]).toEqual(engineEnum('provider'))
  })

  it('变量类型与 VariableSpec.type 完全一致', () => {
    expect([...VARIABLE_TYPES]).toEqual(engineEnum('type', 'VariableSpec'))
  })

  it('节点 type 与引擎判别联合完全一致，且 NODE_TYPE_METAS 全覆盖', () => {
    const fromEngine = engineNodeTypes()
    expect(fromEngine.length).toBe(11)
    expect([...NODE_TYPES]).toEqual(fromEngine)
    expect([...NODE_TYPE_METAS.map((m) => m.type)].sort()).toEqual([...NODE_TYPES].sort())
  })

  it('执行 status 与 MySQL workflow_execution.status 完全一致', () => {
    expect([...EXECUTION_STATUSES]).toEqual(engineExecutionStatuses())
    // 手写 api.ts 里的联合类型也不能漂移（CT-2 生成后由生成物接管）
    const line = read('frontend/src/types/api.ts')
      .split('\n')
      .find((l) => l.includes("status: 'PENDING' |"))
    expect(line).toBeDefined()
    expect([...line!.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1]).sort()).toEqual(
      [...EXECUTION_STATUSES].sort(),
    )
  })

  it('MisfirePolicy 与 Java 枚举常量完全一致', () => {
    expect([...MISFIRE_POLICY_VALUES]).toEqual(engineMisfirePolicies())
    const line = read('frontend/src/types/api.ts')
      .split('\n')
      .find((l) => l.includes('export type MisfirePolicy'))
    expect(line).toBeDefined()
    expect([...line!.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1])).toEqual([
      ...MISFIRE_POLICY_VALUES,
    ])
  })

  it('审批人规则 / 角色 / 超时动作与引擎 Literal 完全一致', () => {
    expect([...APPROVER_TYPES]).toEqual(engineEnum('type', 'ApproverRule'))
    expect([...APPROVER_ROLES]).toEqual(engineEnum('role', 'ApproverRule'))
    expect([...TIMEOUT_ACTIONS]).toEqual(engineEnum('action', 'GateTimeout'))
  })

  it('节点级 timeout_ms / retry 是引擎 NodeBase 的顶层字段', () => {
    const src = read(MODELS)
    const nodeBase = src.slice(src.indexOf('class NodeBase'), src.indexOf('class StartNode'))
    expect(nodeBase).toMatch(/timeout_ms:/)
    expect(nodeBase).toMatch(/retry:/)
  })
})

describe('CT-1 防漂移：源码里不再有手写枚举副本', () => {
  it('ConfigDrawer.vue 的 DSL 下拉全部 v-for，且没有硬编码 <option value>', () => {
    const src = read('frontend/src/components/canvas/ConfigDrawer.vue')
    // 曾被抄两份的两组枚举字面量必须消失
    for (const literal of [
      'value="LATEST_PUBLISHED"',
      'value="FIXED"',
      'value="all"',
      'value="first_success"',
      'value="ROLE"',
      'value="OWNER"',
      'value="REJECT"',
      'value="openai-compatible"',
    ]) {
      expect(src, `ConfigDrawer.vue 仍有硬编码 ${literal}`).not.toContain(literal)
    }
    for (const binding of [
      'SUBWORKFLOW_VERSION_POLICIES',
      'PARALLEL_JOIN_POLICIES',
      'APPROVER_TYPES',
      'APPROVER_ROLES',
      'TIMEOUT_ACTIONS',
      'LLM_PROVIDERS',
    ]) {
      expect(src, `ConfigDrawer.vue 未使用共享枚举 ${binding}`).toContain(binding)
    }
  })

  it('TriggersView.vue 的策略取值来自共享枚举，不抄字面量', () => {
    const src = read('frontend/src/views/TriggersView.vue')
    expect(src).toContain('MISFIRE_POLICY_VALUES')
    expect(src).not.toContain("value: 'CATCH_UP_LATEST'")
  })

  it('types.ts 的联合类型从 contract-enums 派生', () => {
    const src = read('frontend/src/dsl/types.ts')
    expect(src).toContain("from './contract-enums'")
    expect(src).not.toContain("type: 'string' | 'number'")
  })
})
