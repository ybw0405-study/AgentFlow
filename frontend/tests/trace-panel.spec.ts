/** 追踪面板：traceId 外链只在后端下发 LangFuse 地址时出现，href 由运行时数据拼出。 */

import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { ExecutionDto } from '@/types/api'
import TracePanel from '@/components/execution/TracePanel.vue'

function execution(): ExecutionDto {
  return {
    seq: 1,
    id: 'ce79f253-5d00-4ffa-b760-b6065223c685',
    definitionId: 'wf-demo',
    versionId: 'wfv-demo-1',
    triggeredBy: 'u-admin',
    status: 'SUCCEEDED',
    traceId: 'affdeb66affdeb66affdeb66affdeb66',
    tokenBudget: 50000,
    maxSteps: 50,
    tokenUsed: 120,
    stepsUsed: 5,
    errorCode: null,
    startedAt: '2026-11-20T03:28:55',
    finishedAt: '2026-11-20T03:29:01',
    createdAt: '2026-11-20T03:28:55',
  }
}

describe('TracePanel 追踪链接', () => {
  it('未配置 baseUrl 时 traceId 保持纯文本', () => {
    const wrapper = mount(TracePanel, { props: { execution: execution() } })
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.text()).toContain('affdeb66affdeb66affdeb66affdeb66')
  })

  it('baseUrl 为空串/null 同样不出链接', () => {
    for (const base of ['', null]) {
      const wrapper = mount(TracePanel, {
        props: { execution: execution(), langfuseBaseUrl: base },
      })
      expect(wrapper.find('a').exists()).toBe(false)
    }
  })

  it('配置后渲染外链：href 运行时拼接、新窗口打开、noopener noreferrer', () => {
    const wrapper = mount(TracePanel, {
      props: {
        execution: execution(),
        langfuseBaseUrl: 'http://127.0.0.1:13000/project/p1/traces/',
      },
    })
    const link = wrapper.find('a')
    expect(link.exists()).toBe(true)
    // 尾斜杠归一：base + traceId 恰好一个分隔符
    expect(link.attributes('href')).toBe(
      'http://127.0.0.1:13000/project/p1/traces/affdeb66affdeb66affdeb66affdeb66',
    )
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(link.text()).toBe('affdeb66affdeb66affdeb66affdeb66')
  })
})