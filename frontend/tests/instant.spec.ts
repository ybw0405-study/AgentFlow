import { describe, expect, it } from 'vitest'

import { formatInstant } from '@/utils/instant'

const pad = (n: number) => String(n).padStart(2, '0')

function localText(iso: string): string {
  const d = new Date(iso)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

describe('formatInstant', () => {
  it('把带 UTC 偏移的瞬间按本地时区渲染，而不是截掉偏移显示 UTC', () => {
    const iso = '2026-11-20T13:20:49.404+00:00'
    expect(formatInstant(iso)).toBe(localText(iso))
    // 早先 slice(0, 19) 的结果里带 T，且小时数不是本地小时。
    expect(formatInstant(iso)).not.toContain('T')
    expect(formatInstant(iso)).not.toBe('2026-11-20T13:20:49')
  })

  it('Z 结尾与 +00:00 结尾等价', () => {
    expect(formatInstant('2026-11-20T13:20:49Z')).toBe(formatInstant('2026-11-20T13:20:49+00:00'))
  })

  it('不带偏移时按后端约定的 UTC 解释，不当作本地墙上时间', () => {
    expect(formatInstant('2026-11-20T13:20:49')).toBe(formatInstant('2026-11-20T13:20:49Z'))
  })

  it('空值走 fallback，无效串原样返回', () => {
    expect(formatInstant(null)).toBe('—')
    expect(formatInstant(undefined, '—')).toBe('—')
    expect(formatInstant('')).toBe('—')
    expect(formatInstant('not-a-date')).toBe('not-a-date')
  })
})
