/** 设计红线守卫（暗物质）：原始色值只允许出现在 design/tokens.css 与 design/base.css；
 * 任何文件（含这两个）禁止绿色。 */

import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const SRC = join(__dirname, '..', 'src')
const COLOR_LITERAL = /#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/i
const GREEN = /#0f0\b|#00ff00\b|#22c55e|#16a34a|#4ade80|#10b981|\bgreen\b|\blime\b/i
const ALLOWED_RAW_COLOR_FILES = new Set(['tokens.css', 'base.css'])

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (/\.(vue|css|ts)$/.test(entry)) out.push(full)
  }
  return out
}

describe('dark matter red lines', () => {
  const files = walk(SRC)

  it('no raw color literals outside the design token files', () => {
    const offenders: string[] = []
    for (const file of files) {
      if (ALLOWED_RAW_COLOR_FILES.has(file.split(/[\\/]/).pop() ?? '')) continue
      const content = readFileSync(file, 'utf-8')
      // 模板中 rgba(255,255,255,·) 级别的边框微调允许？——不允许，一律走令牌
      if (COLOR_LITERAL.test(content)) offenders.push(file)
    }
    expect(offenders).toEqual([])
  })

  it('no green anywhere, including token files', () => {
    const offenders: string[] = []
    for (const file of files) {
      if (GREEN.test(readFileSync(file, 'utf-8'))) offenders.push(file)
    }
    expect(offenders).toEqual([])
  })
})
