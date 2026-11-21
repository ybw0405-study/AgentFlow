import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { PARALLEL_JOIN_POLICIES, SUBWORKFLOW_VERSION_POLICIES } from '@/dsl/node-card'

// 前端内联下拉与引擎 Pydantic 模型曾经各写一份，PINNED / any / n_of_m 三个值
// 引擎根本不认，用户在卡片上选完草稿就保存失败。这里把两份定义钉成同一份事实。
// Vite 下 import.meta.url 是 http scheme，不能用 fileURLToPath；按 cwd 探测两种入口。
const modelsPath = [
  resolve(process.cwd(), '../engine-api/app/dsl/models.py'),
  resolve(process.cwd(), 'engine-api/app/dsl/models.py'),
].find((candidate) => existsSync(candidate))
if (modelsPath === undefined) {
  throw new Error('找不到 engine-api/app/dsl/models.py，无法做枚举契约对账')
}
const models = readFileSync(modelsPath, 'utf8')

function engineEnum(field: string): string[] {
  const match = new RegExp(`${field}: Literal\\[([^\\]]+)\\]`).exec(models)
  expect(match, `engine models.py 缺少 ${field} 的 Literal 定义`).not.toBeNull()
  return [...match![1].matchAll(/"([^"]+)"/g)].map((item) => item[1])
}

describe('DSL 枚举与引擎模型对齐', () => {
  it('subworkflow 版本策略与 SubworkflowConfig.version_policy 完全一致', () => {
    expect([...SUBWORKFLOW_VERSION_POLICIES]).toEqual(engineEnum('version_policy'))
  })

  it('parallel 汇聚策略与 ParallelConfig.join 完全一致', () => {
    expect([...PARALLEL_JOIN_POLICIES]).toEqual(engineEnum('join'))
  })

  it('节点级 timeout_ms / retry 是引擎 NodeBase 的顶层字段', () => {
    const nodeBase = models.slice(models.indexOf('class NodeBase'), models.indexOf('class StartNode'))
    expect(nodeBase).toMatch(/timeout_ms:/)
    expect(nodeBase).toMatch(/retry:/)
  })
})
