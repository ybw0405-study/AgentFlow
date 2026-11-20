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
      // 本月试跑：账本里已用 4000，其中 1000 来自试跑
      previewCalls: 4,
      previewTokens: 1000,
      previewCallUtilization: 0.004,
      previewTokenUtilization: 0.02,
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
    byModel: [
      {
        model: 'deepseek-chat',
        executions: 10,
        executionTokens: 2500,
        previews: 3,
        previewTokens: 800,
        tokens: 3300,
      },
      {
        model: null,
        executions: 2,
        executionTokens: 500,
        previews: 1,
        previewTokens: 200,
        tokens: 700,
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

  it('额度条把试跑的一段单独切出来，并写明它在已用里的占比', async () => {
    const wrapper = await mountView()

    // 本月 token 已用 8%，其中试跑 1000/50000 = 2%，执行段就是剩下的 6%
    const segments = wrapper.findAll('.af-usage-bar__fill--preview')
    expect(segments).toHaveLength(2)
    expect((segments[1].element as HTMLElement).style.width).toBe('2%')
    const execBar = wrapper.findAll('.af-usage-bar__fill').filter((n) => !n.classes('af-usage-bar__fill--preview'))
    expect((execBar[1].element as HTMLElement).style.width).toBe('6%')
    expect(wrapper.text()).toContain('其中试跑 1,000（占已用 25%）')
  })

  it('试跑份额大于已用量时按已用量封顶，不把墨迹画出进度条', async () => {
    // 记账失败或手工改库时会出现这种不一致：宁可少画，也不要画出界
    mocks.summary.mockResolvedValue(
      summary({
        budget: {
          period: '2026-11',
          callsUsed: 25000,
          tokensUsed: 25000,
          callLimit: 50000,
          tokenLimit: 50000,
          callUtilization: 0.5,
          tokenUtilization: 0.5,
          previewCalls: 100,
          previewTokens: 40000,
          previewCallUtilization: 0.002,
          previewTokenUtilization: 0.8,
        },
      }),
    )
    const wrapper = await mountView()

    const preview = wrapper.findAll('.af-usage-bar__fill--preview')
    expect((preview[1].element as HTMLElement).style.width).toBe('50%')
    // 执行段被压到 0：两段加起来不超已用量
    const exec = wrapper.findAll('.af-usage-bar__fill').filter((n) => !n.classes('af-usage-bar__fill--preview'))
    expect((exec[1].element as HTMLElement).style.width).toBe('0%')
  })

  it('不足 1% 的试跑段退化成可见刻度，而不是整段消失', async () => {
    // 50000 额度里只有 115 token 试跑 = 0.23%：宽度只有零点几像素，
    // 取整成 0% 就等于对外说「没有试跑」，与脚注「占 <1%」自相矛盾。
    mocks.summary.mockResolvedValue(
      summary({
        budget: {
          period: '2026-11',
          callsUsed: 120,
          tokensUsed: 4000,
          callLimit: 10000,
          tokenLimit: 50000,
          callUtilization: 0.012,
          tokenUtilization: 0.08,
          previewCalls: 1,
          previewTokens: 115,
          previewCallUtilization: 0.0001,
          previewTokenUtilization: 0.0023,
        },
      }),
    )
    const wrapper = await mountView()

    const preview = wrapper.findAll('.af-usage-bar__fill--preview')
    // 调用数那一档 0.01%、token 那一档 0.23%：宽度都落在 1px 以下，靠最小宽度可见
    expect((preview[0].element as HTMLElement).style.width).toBe('0%')
    expect((preview[1].element as HTMLElement).style.width).toBe('0.2%')
    expect(preview[0].classes()).toContain('af-usage-bar__fill--tick')
    expect(preview[1].classes()).toContain('af-usage-bar__fill--tick')
  })

  it('试跑为 0 时不挂最小宽度，避免画出「有一点试跑」的假象', async () => {
    mocks.summary.mockResolvedValue(
      summary({
        budget: {
          period: '2026-11',
          callsUsed: 120,
          tokensUsed: 4000,
          callLimit: 10000,
          tokenLimit: 50000,
          callUtilization: 0.012,
          tokenUtilization: 0.08,
          previewCalls: 0,
          previewTokens: 0,
        },
      }),
    )
    const wrapper = await mountView()

    const preview = wrapper.findAll('.af-usage-bar__fill--preview')
    expect(preview[0].classes()).not.toContain('af-usage-bar__fill--tick')
    expect((preview[0].element as HTMLElement).style.width).toBe('0%')
  })

  it('按模型分解：同一模型合并两来源，没有模型的节点单独一桶', async () => {
    const wrapper = await mountView()

    const text = wrapper.text()
    expect(text).toContain('按模型')
    expect(text).toContain('deepseek-chat')
    expect(text).toContain('2,500')
    expect(text).toContain('800')
    // 非 LLM / 输出不带模型的行显式标注，读者不用猜为什么是空的
    expect(text).toContain('未标注')
    expect(text).toContain('非 LLM 或输出未带模型')
    expect(text).toContain('83%')
  })

  it('最近试跑列出模型与 token，账面对不上时可追到具体节点', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('draft')
    expect(text).toContain('deepseek-chat')
    expect(text).toContain('321')
  })

  it('试跑占比不足 1% 时写明「<1%」，不把非零消耗写成 0%', async () => {
    mocks.summary.mockResolvedValue(
      summary({
        budget: {
          period: '2026-11',
          callsUsed: 2448,
          tokensUsed: 132501,
          callLimit: 10000,
          tokenLimit: 50000,
          callUtilization: 0.2448,
          tokenUtilization: 1,
          previewCalls: 40,
          previewTokens: 115,
          previewCallUtilization: 0.004,
          previewTokenUtilization: 0.0023,
        },
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('其中试跑 115（占已用 <1%）')
    expect(wrapper.text()).not.toContain('占已用 0%')
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

  it('后端省略空字段时（无模型 / 无耗时）仍能正常渲染', async () => {
    // 服务端对 null 字段不输出 key：模板不能拿 === null 去判断
    mocks.summary.mockResolvedValue(
      summary({
        recentPreviews: [
          {
            id: 'pv-2',
            definitionId: 'wf-1',
            definitionName: null,
            nodeKey: 'norm',
            actorId: null,
            status: 'SUCCEEDED',
            tokenUsed: 0,
            createdAt: '2026-11-20 10:05:00',
          } as unknown as UsageSummary['recentPreviews'][number],
        ],
      }),
    )
    const wrapper = await mountView()

    const text = wrapper.text()
    expect(text).toContain('norm')
    expect(text).toContain('—')
    expect(text).not.toContain('undefined')
  })

  it('后端失败时给出可读错误，而不是空白页', async () => {
    mocks.summary.mockRejectedValue(
      new ApiError({ status: 503, code: 'AGT-UPSTREAM-001', detail: '引擎暂时不可用', traceId: 't-1' }),
    )
    const wrapper = await mountView()
    expect(wrapper.find('.af-error-box').exists()).toBe(true)
  })
})
