import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import { ApiError } from '@/api/http'
import UsageView from '@/views/UsageView.vue'
import type { UsageSummary } from '@/types/api'

const mocks = vi.hoisted(() => ({ summary: vi.fn() }))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return { ...actual, usageApi: { summary: mocks.summary } }
})

function summary(overrides: Partial<UsageSummary> = {}): UsageSummary {
  return {
    from: '2026-10-22',
    to: '2026-11-20',
    days: 30,
    executions: { count: 12, tokens: 3000 },
    previews: { count: 4, tokens: 1000 },
    totalTokens: 4000,
    previewShare: 0.25,
    budget: {
      period: '2026-11',
      callsUsed: 120,
      tokensUsed: 4000,
      callLimit: 10000,
      tokenLimit: 50000,
      callUtilization: 0.012,
      tokenUtilization: 0.08,
    },
    byDefinition: [
      {
        definitionId: 'wf-1',
        name: '售后问答',
        executions: 12,
        executionTokens: 3000,
        previews: 4,
        previewTokens: 1000,
        tokens: 4000,
      },
    ],
    daily: [
      { date: '2026-11-19', executionTokens: 500, previewTokens: 0, tokens: 500 },
      { date: '2026-11-20', executionTokens: 1000, previewTokens: 300, tokens: 1300 },
    ],
    recentPreviews: [
      {
        id: 'pv-1',
        definitionId: 'wf-1',
        definitionName: '售后问答',
        nodeKey: 'draft',
        actorId: 'u-1',
        status: 'SUCCEEDED',
        model: 'deepseek-chat',
        tokenUsed: 321,
        latencyMs: 1200,
        createdAt: '2026-11-20 10:00:00',
      },
    ],
    ...overrides,
  }
}

async function mountView(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(UsageView)
  await flushPromises()
  return wrapper
}

describe('用量页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.summary.mockResolvedValue(summary())
  })

  it('同时展示执行与试跑的消耗，并给出试跑占比', async () => {
    const wrapper = await mountView()

    expect(mocks.summary).toHaveBeenCalledWith(30)
    const text = wrapper.text()
    expect(text).toContain('3,000')
    expect(text).toContain('1,000')
    expect(text).toContain('占 25%')
  })

  it('切换区间重新拉取，并按天画出柱子', async () => {
    const wrapper = await mountView()
    const week = wrapper.findAll('button').find((b) => b.text() === '近 7 天')
    await week!.trigger('click')
    await flushPromises()

    expect(mocks.summary).toHaveBeenLastCalledWith(7)
    expect(wrapper.findAll('.af-usage-chart__column')).toHaveLength(2)
    // 试跑柱子与执行柱子分开堆叠，颜色语义不同
    expect(wrapper.findAll('.af-usage-chart__bar--preview')).toHaveLength(2)
  })

  it('最近试跑列出模型与 token，账面对不上时可追到具体节点', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('draft')
    expect(text).toContain('deepseek-chat')
    expect(text).toContain('321')
  })

  it('额度用完时把提示换成会拒绝执行的结论', async () => {
    mocks.summary.mockResolvedValue(
      summary({
        budget: {
          period: '2026-11',
          callsUsed: 10000,
          tokensUsed: 50000,
          callLimit: 10000,
          tokenLimit: 50000,
          callUtilization: 1,
          tokenUtilization: 1,
        },
      }),
    )
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('额度已用完')
  })

  it('后端失败时给出可读错误，而不是空白页', async () => {
    mocks.summary.mockRejectedValue(
      new ApiError({ status: 503, code: 'AGT-UPSTREAM-001', detail: '引擎暂时不可用', traceId: 't-1' }),
    )
    const wrapper = await mountView()
    expect(wrapper.find('.af-error-box').exists()).toBe(true)
  })
})
