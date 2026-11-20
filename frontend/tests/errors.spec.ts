import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/http'
import { describeError, errorDebugInfo, errorText } from '@/api/errors'

function apiError(status: number, code: string | undefined, detail = 'x'): ApiError {
  return new ApiError({ status, title: 'T', detail, code, traceId: 'trace-1' } as never)
}

describe('错误文案映射', () => {
  it('已知业务码翻译为可读文案，不把码放进正文', () => {
    const d = describeError(apiError(403, 'WF-FORBIDDEN-001'))
    expect(d.text).toBe('当前账号没有此操作权限')
    expect(d.hint).toContain('管理员')
    expect(d.text).not.toContain('WF-')
  })

  it('并发上限给出可执行的提示，而不是只说「太频繁」', () => {
    const d = describeError(apiError(429, 'WF-CONCURRENCY-001'))
    expect(d.text).toContain('同时在跑的执行已达上限')
    expect(d.hint).toContain('触发页')
    expect(d.text).not.toContain('WF-')
  })

  it('保留内部码与 traceId 供排查', () => {
    const e = apiError(422, 'DSL-ORPHAN-001')
    expect(errorDebugInfo(e)).toBe('DSL-ORPHAN-001 · trace-1')
  })

  it('未收录的码按前缀归类，不把原始码当正文', () => {
    const d = describeError(apiError(400, 'CONN-SOMETHING-NEW-001'))
    expect(d.text).toBe('连接器调用未成功')
    expect(d.text).not.toContain('CONN-')
  })

  it('无业务码时回落到 HTTP 状态语义', () => {
    expect(describeError(apiError(429, undefined)).text).toBe('操作过于频繁')
    expect(describeError(apiError(503, undefined)).text).toBe('服务正在维护')
  })

  it('网络异常给出可执行的提示', () => {
    const d = describeError(new Error('Network Error'))
    expect(d.text).toBe('网络不可达')
    expect(d.hint).toBeTruthy()
  })

  it('未知异常不泄露原始 message', () => {
    const d = describeError(new Error('TypeError: cannot read x of undefined'))
    expect(d.text).toBe('操作未成功')
    expect(d.text).not.toContain('TypeError')
  })

  it('hint 与正文拼接成一句话', () => {
    expect(errorText(apiError(401, 'USR-TOKEN-001'))).toBe('登录状态已过期，请重新登录')
  })

  it('后端写的具体原因作为补充行保留，方便说明为什么被拒', () => {
    const d = describeError(apiError(409, 'WF-STATE-001', 'invalid cron expression: 0 9 * * 8'))
    expect(d.text).toBe('当前状态不允许此操作')
    expect(d.detail).toBe('invalid cron expression: 0 9 * * 8')
  })

  it('detail 与正文重复或是 HTTP 兜底文案时不再重复展示', () => {
    expect(describeError(apiError(409, 'WF-STATE-001', '当前状态不允许此操作')).detail).toBe(null)
    expect(describeError(apiError(500, undefined, 'HTTP 500')).detail).toBe(null)
  })

  it('普通异常不带 detail', () => {
    expect(describeError(new Error('boom')).detail).toBe(null)
  })
})
