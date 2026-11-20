/**
 * 审批人表达式的前端体检（真正的权威校验在引擎发布期与 Java 求值侧）。
 *
 * 这里只做「别让人拖着一个必然发布失败的工作流」这一层：词法能过、属性名在白名单里。
 * 文法子集刻意与 Java `GateExpression`、Python `_expr_problem` 完全一致——
 * 三处都只认 user/space 上的固定属性、比较、in/not in、and/or/not、列表字面量。
 */

export const APPROVER_ATTRS: Record<string, string[]> = {
  user: ['id', 'role', 'rank', 'platformAdmin'],
  space: ['id'],
}

const LITERALS = new Set(['true', 'false', 'null', 'none'])
const OPERATORS = new Set(['and', 'or', 'not', 'in'])
const COMPARATORS = new Set(['==', '!=', '<', '<=', '>', '>='])

/** 关键字大小写不敏感（与 Java `GateExpression` 一致）；标识符区分大小写。 */
const isKeyword = (token: string | undefined, word: string): boolean =>
  (token ?? '').toLowerCase() === word

const TOKEN_RE = /"[^"]*"|'[^']*'|\d+|[A-Za-z_][A-Za-z0-9_]*|==|!=|<=|>=|[<>()[\],.]/g

/** 返回第一条错误；`null` 表示通过。 */
export function approverExpressionError(expression: string): string | null {
  const expr = expression.trim()
  if (!expr) return '请填写审批人表达式'

  // 允许的字符集之外的东西（正则 token 化会静默跳过它们，必须单独拦）。
  // 字符串字面量内部不受限（用户 id 可能带 - 或 .），先整体抹掉再检查。
  const bare = expr.replace(/"[^"]*"|'[^']*'/g, '""')
  if (/[^A-Za-z0-9_"'.\s,[\]()<>=!]/.test(bare)) return '包含不支持的字符'
  const doubleQuotes = (expr.match(/"/g) ?? []).length
  const singleQuotes = (expr.match(/'/g) ?? []).length
  if (doubleQuotes % 2 !== 0 || singleQuotes % 2 !== 0) return '字符串未闭合'

  const tokens = expr.match(TOKEN_RE) ?? []
  let expectValue = true
  let depth = 0
  let i = 0
  while (i < tokens.length) {
    const token = tokens[i]
    if (expectValue) {
      if (token === '(') {
        depth += 1
        i += 1
        continue
      }
      if (isKeyword(token, 'not')) {
        i += 1
        continue
      }
      if (token === '[') {
        const end = consumeList(tokens, i)
        if (end < 0) return '列表字面量不合法'
        i = end
        expectValue = false
        continue
      }
      if (/^["']/.test(token) || /^\d+$/.test(token)) {
        expectValue = false
        i += 1
        continue
      }
      if (LITERALS.has(token.toLowerCase()) || OPERATORS.has(token.toLowerCase())) {
        expectValue = false
        i += 1
        continue
      }
      if (token in APPROVER_ATTRS) {
        if (tokens[i + 1] !== '.') return `${token} 后必须跟属性名`
        const attr = tokens[i + 2]
        const allowed = APPROVER_ATTRS[token]
        if (!attr || !allowed.includes(attr)) {
          return `未知属性 ${token}.${attr ?? '?'}（可用：${allowed.join(' / ')}）`
        }
        expectValue = false
        i += 3
        continue
      }
      return `无法解析的标识符 ${token}`
    }

    if (token === ')' || token === ']') {
      depth -= 1
      if (depth < 0) return '括号不匹配'
      i += 1
      continue
    }
    if (isKeyword(token, 'and') || isKeyword(token, 'or') || isKeyword(token, 'in')) {
      expectValue = true
      i += 1
      continue
    }
    if (isKeyword(token, 'not') && isKeyword(tokens[i + 1], 'in')) {
      expectValue = true
      i += 2
      continue
    }
    if (COMPARATORS.has(token)) {
      expectValue = true
      i += 1
      continue
    }
    return `运算符位置不正确：${token}`
  }
  if (expectValue) return '表达式不完整'
  if (depth !== 0) return '括号不匹配'
  return null
}

/** 从 `[` 起消费一个列表字面量，返回其后的下标；`-1` 表示不合法。 */
function consumeList(tokens: string[], start: number): number {
  let i = start + 1
  let needValue = true
  while (i < tokens.length) {
    const token = tokens[i]
    if (token === ']') {
      return needValue && i !== start + 1 ? -1 : i + 1
    }
    if (needValue) {
      const isValue =
        /^["']/.test(token) || /^\d+$/.test(token) || LITERALS.has(token.toLowerCase())
      if (!isValue) return -1
      needValue = false
    } else if (token === ',') {
      needValue = true
    } else {
      return -1
    }
    i += 1
  }
  return -1
}
