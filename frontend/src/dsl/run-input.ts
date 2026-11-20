import type { VariableSpec } from './types'

/**
 * 运行前检查必填入参。
 *
 * 引擎的 start 节点也会校验并返回 EXEC-INPUT-001，但那要等一次执行已经创建、
 * 已经进入执行列表才看得到。这里先挡一层，省掉一条无用的执行记录。
 */
export function missingRequired(
  variables: Record<string, VariableSpec>,
  values: Record<string, unknown>,
): string[] {
  const missing: string[] = []
  for (const [name, spec] of Object.entries(variables)) {
    if (!spec.required) continue
    const value = values[name]
    if (value === undefined || value === null) {
      missing.push(name)
      continue
    }
    if (spec.type === 'string' && value === '') missing.push(name)
  }
  return missing
}
