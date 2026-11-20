import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import { ApiError } from '@/api/http'
import AdminView from '@/views/AdminView.vue'
import type { AdminTenantUsage, AdminUsage } from '@/api'

const mocks = vi.hoisted(() => ({ usage: vi.fn(), audit: vi.fn() }))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return { ...actual, adminApi: { usage: mocks.usage, audit: mocks.audit } }
})

function tenant(overrides: Partial<AdminTenantUsage> = {}): AdminTenantUsage {
  return {
    tenantId: 't-space-a',
    name: 'Space Alpha',
    type: 'SPACE',
    parentId: 't-team-core',
    callsUsed: 900,
    tokensUsed: 9500,
    previewCalls: 40,
    previewTokens: 500,
    executions: 10,
    executionsFailed: 2,
    ownCallLimit: 9000,
    ownTokenLimit: 90000,
    effectiveCallLimit: 1000,
    effectiveTokenLimit: 10000,
    limitedByCall: 't-org-demo',
    limitedByCallName: 'Demo Org',
    limitedByToken: 't-org-demo',
    limitedByTokenName: 'Demo Org',
    inherited: true,
    state: 'near',
    callUtilization: 0.9,
    tokenUtilization: 0.95,
    ...overrides,
  }
}

function usage(overrides: Partial<AdminUsage> = {}): AdminUsage {
  return {
    period: '2026-11',
    from: '2026-11-01',
    tenants: [tenant()],
    totals: {
      tenants: 1,
      calls: 900,
      tokens: 9500,
      previewCalls: 40,
      previewTokens: 500,
      previewShare: 0.0526,
      executions: 10,
      executionsFailed: 2,
      over: 0,
      near: 1,
    },
    byModel: [
      { model: 'deepseek-chat', executionTokens: 8000, previewTokens: 300, tokens: 8300, tenants: 2 },
      { model: null, executionTokens: 550, previewTokens: 50, tokens: 600, tenants: 1 },
    ],
    ...overrides,
  }
}

async function mountView(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(AdminView)
  await flushPromises()
  return wrapper
}

describe('管理端成本中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.usage.mockResolvedValue(usage())
    mocks.audit.mockResolvedValue({ items: [], nextCursor: null })
  })

  it('租户行给出有效限额并标出是被谁卡住的', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()

    // 自身账本写着 9,000 / 90,000，但生效的是父的 1,000 / 10,000
    expect(text).toContain('1,000 / 10,000')
    expect(text).toContain('继承自 Demo Org')
    expect(text).toContain('Space Alpha')
    expect(text).toContain('SPACE')
  })

  it('限额来自自己时不标「继承」，避免管理员去改错的账本', async () => {
    mocks.usage.mockResolvedValue(
      usage({
        tenants: [
          tenant({
            tenantId: 't-org-demo',
            name: 'Demo Org',
            type: 'ORG',
            parentId: null,
            inherited: false,
            limitedByCall: 't-org-demo',
            limitedByToken: 't-org-demo',
            limitedByTokenName: 'Demo Org',
            state: 'ok',
          }),
        ],
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('本租户')
    expect(wrapper.text()).not.toContain('继承自')
  })

  it('没有账本行的租户不画额度条，限额显示「无上限」', async () => {
    mocks.usage.mockResolvedValue(
      usage({
        tenants: [
          tenant({
            callsUsed: 0,
            tokensUsed: 0,
            previewCalls: 0,
            previewTokens: 0,
            ownCallLimit: null,
            ownTokenLimit: null,
            effectiveCallLimit: null,
            effectiveTokenLimit: null,
            limitedByCall: null,
            limitedByToken: null,
            limitedByTokenName: null,
            inherited: false,
            state: 'unbounded',
            callUtilization: 0,
            tokenUtilization: 0,
          }),
        ],
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('无上限')
    expect(wrapper.findAll('.af-meter')).toHaveLength(2) // 只剩按模型表里的两条
  })

  it('额度条把执行与试跑分成两段，试跑超出已用时按已用封顶', async () => {
    mocks.usage.mockResolvedValue(
      usage({
        tenants: [tenant({ tokensUsed: 1000, previewTokens: 4000, effectiveTokenLimit: 10000 })],
      }),
    )
    const wrapper = await mountView()

    const preview = wrapper.findAll('.af-meter__fill--preview')
    expect((preview[0].element as HTMLElement).style.width).toBe('10%')
    // 执行段被压到 0：两段加起来不超过已用量
    const exec = wrapper.findAll('.af-meter__fill').filter((n) => !n.classes('af-meter__fill--preview'))
    expect((exec[0].element as HTMLElement).style.width).toBe('0%')
  })

  it('超限租户标成「已超限」并用危险色，汇总里单独计数', async () => {
    mocks.usage.mockResolvedValue(
      usage({
        tenants: [tenant({ state: 'over', tokenUtilization: 1 })],
        totals: {
          tenants: 1,
          calls: 900,
          tokens: 9500,
          previewCalls: 40,
          previewTokens: 500,
          previewShare: 0.0526,
          executions: 10,
          executionsFailed: 2,
          over: 1,
          near: 0,
        },
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('已超限')
    expect(wrapper.find('.af-status--failed').exists()).toBe(true)
    expect(wrapper.text()).toContain('已超限 1 · 接近上限 0')
  })

  it('汇总卡片给出平台合计、试跑占比与失败数', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()

    expect(text).toContain('9,500')
    expect(text).toContain('其中试跑 500（占 5%）')
    expect(text).toContain('失败 2')
    expect(text).toContain('试跑 40 次 · 1 个租户')
  })

  it('试跑占比不足 1% 时写「<1%」，不把非零消耗写成 0%', async () => {
    mocks.usage.mockResolvedValue(
      usage({
        totals: {
          tenants: 1,
          calls: 2448,
          tokens: 132501,
          previewCalls: 40,
          previewTokens: 115,
          previewShare: 0.0009,
          executions: 577,
          executionsFailed: 22,
          over: 1,
          near: 0,
        },
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('其中试跑 115（占 <1%）')
    expect(wrapper.text()).not.toContain('占 0%')
  })

  it('按模型表合并两来源，空模型单独标注，租户数是并集', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()

    expect(text).toContain('deepseek-chat')
    expect(text).toContain('8,000')
    expect(text).toContain('未标注')
    expect(text).toContain('非 LLM 或输出未带模型')
    expect(text).toContain('93%')
    expect(wrapper.findAll('.af-table')[1].text()).toContain('2')
  })

  it('切换账期重新拉取，并且不会把空账期发出去', async () => {
    const wrapper = await mountView()
    mocks.usage.mockClear()

    const input = wrapper.find('input[type="month"]')
    await input.setValue('2026-10')
    await input.trigger('change')
    await flushPromises()
    expect(mocks.usage).toHaveBeenLastCalledWith('2026-10')

    // 清空输入框时按「本月」处理：不能把空串当账期发给后端
    await input.setValue('')
    await input.trigger('change')
    await flushPromises()
    expect(mocks.usage).toHaveBeenLastCalledWith(undefined)
  })

  it('拉取失败时把原因显示出来，而不是留一张空表', async () => {
    mocks.usage.mockRejectedValue(new ApiError({
      status: 400,
      title: 'Bad Request',
      detail: 'period must be YYYY-MM, got: 2026-8',
      code: 'COM-VALIDATION-001',
      traceId: 'trace-1',
    } as never))
    const wrapper = await mountView()

    // 面向用户的那句来自错误码表，后端的具体原因作为第二行保留
    expect(wrapper.text()).toContain('提交的内容不符合要求')
    expect(wrapper.text()).toContain('period must be YYYY-MM')
  })

  it('账期没有账本行时给出空态而不是空白', async () => {
    mocks.usage.mockResolvedValue(
      usage({
        tenants: [],
        byModel: [],
        totals: {
          tenants: 0,
          calls: 0,
          tokens: 0,
          previewCalls: 0,
          previewTokens: 0,
          previewShare: 0,
          executions: 0,
          executionsFailed: 0,
          over: 0,
          near: 0,
        },
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('这个账期还没有账本行')
    expect(wrapper.text()).toContain('这个账期没有消耗')
  })

  it('切到审计页仍然按游标翻页', async () => {
    mocks.audit.mockResolvedValue({
      items: [
        {
          seq: 7,
          id: 'a-1',
          tenantId: 't-space-a',
          traceId: 'trace-abcdef123456',
          eventType: 'TRIGGER_FIRED',
          createdAt: '2026-11-20 10:00:00',
        },
      ],
      nextCursor: 'cursor-2',
    })
    const wrapper = await mountView()

    const auditTab = wrapper.findAll('button').find((b) => b.text() === '审计')
    await auditTab!.trigger('click')
    await flushPromises()

    expect(mocks.audit).toHaveBeenCalledWith({ cursor: undefined })
    expect(wrapper.text()).toContain('TRIGGER_FIRED')

    const more = wrapper.findAll('button').find((b) => b.text() === '加载更多')
    await more!.trigger('click')
    await flushPromises()
    expect(mocks.audit).toHaveBeenLastCalledWith({ cursor: 'cursor-2' })
  })
})
