import type { VariableSpec } from './types'

/**
 * 运行弹窗里 initialVariables 的初值。
 *
 * 引擎 start 节点会按 DSL 声明的类型逐个校验（EXEC-INPUT-001），因此不能给所有变量
 * 统一塞空串：boolean / number / integer / object / array 拿到 '' 会让执行在第一个
 * 节点就 FAILED，而用户往往只是没改弹窗里的默认值。这里按声明类型生成合法零值，
 * 声明了 default 时优先用 default。
 */
const ZERO_VALUE: Record<VariableSpec['type'], unknown> = {
  string: '',
  number: 0,
  integer: 0,
  boolean: false,
  object: {},
  array: [],
}

export function initialVariables(
  variables: Record<string, VariableSpec>,
): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const [name, spec] of Object.entries(variables)) {
    result[name] =
      spec.default !== undefined && spec.default !== null
        ? spec.default
        : ZERO_VALUE[spec.type]
  }
  return result
}
