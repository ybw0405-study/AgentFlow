import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import { ApiError } from '@/api/http'
import TriggersView from '@/views/TriggersView.vue'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  list: vi.fn(),
  create: vi.fn(),
  patch: vi.fn(),
  rotate: vi.fn(),
  remove: vi.fn(),
  previewCron: vi.fn(),
  workflowPatch: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    workflowApi: { ...actual.workflowApi, detail: mocks.detail, patch: mocks.workflowPatch },
    triggerApi: {
      list: mocks.list,
      create: mocks.create,
      patch: mocks.patch,
      rotate: mocks.rotate,
      remove: mocks.remove,
      previewCron: mocks.previewCron,
    },
  }
})

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'wf-1' } }) }))

const DRAFT = JSON.stringify({
  dslVersion: '1.0',
  variables: { question: { type: 'string', required: true } },
  nodes: [
    { key: 'start', type: 'start', config: {} },
    { key: 'end', type: 'end', config: { resultMapping: { a: '{{ vars.question }}' } } },
  ],
  edges: [{ from: 'start', to: 'end' }],
})

function detail(published = true) {
  return {
    definition: {
      seq: 1, id: 'wf-1', tenantId: 't', name: '售后问答', description: null,
      status: 'PUBLISHED', currentVersionId: published ? 'v-1' : null,
      maxConcurrent: 0, draftDsl: DRAFT,
      draftChecksum: 'c', createdBy: 'u', createdAt: null, updatedAt: null,
    },
    versions: [],
  }
}

const WEBHOOK = {
  id: 'tr-1', type: 'WEBHOOK' as const, token: 'tok-abc', secret: 'sec-123',
  cron: null, timezone: 'Asia/Shanghai', enabled: true, variables: {},
  versionId: null, lastFiredAt: null, lastSlotAt: null, nextFireAt: null,
  nextFireTimes: [], createdAt: '2026-01-01T00:00:00Z',
}

const SCHEDULE = {
  id: 'tr-2', type: 'SCHEDULE' as const, token: 'tok-sch', secret: 'sec-sch',
  cron: '0 0 9 * * *', timezone: 'Asia/Shanghai', enabled: true,
  misfirePolicy: 'CATCH_UP_LATEST' as const, variables: {},
  versionId: null, lastFiredAt: '2026-11-20T01:37:00Z', lastSlotAt: '2026-11-20 09:00',
  lastOutcome: 'FIRE' as const,
  nextFireAt: '2026-11-21 09:00', nextFireTimes: ['2026-11-21 09:00', '2026-11-22 09:00'],
  createdAt: '2026-01-01T00:00:00Z',
}

async function mountView() {
  const wrapper = mount(TriggersView)
  await flushPromises()
  return wrapper
}

describe('触发页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.detail.mockResolvedValue(detail())
    mocks.list.mockResolvedValue([])
    mocks.create.mockResolvedValue(WEBHOOK)
    mocks.patch.mockResolvedValue(WEBHOOK)
    mocks.rotate.mockResolvedValue(WEBHOOK)
    mocks.remove.mockResolvedValue({ ok: true })
    mocks.workflowPatch.mockResolvedValue({ ok: true })
    mocks.previewCron.mockResolvedValue({
      cron: '0 0 9 * * *',
      timezone: 'Asia/Shanghai',
      times: ['2026-11-21 09:00', '2026-11-22 09:00', '2026-11-23 09:00'],
    })
  })

  it('没有 webhook 时给出创建入口', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('创建 Webhook')
    expect(wrapper.text()).toContain('创建定时触发')
  })

  it('已创建时展示可直接复制的入口地址', async () => {
    mocks.list.mockResolvedValue([WEBHOOK])
    const wrapper = await mountView()
    const url = (wrapper.find('input[readonly]').element as HTMLInputElement).value
    expect(url).toContain('/api/v1/hooks/tok-abc')
    expect(wrapper.text()).toContain('轮换令牌')
  })

  it('创建 webhook 调用后端并刷新列表', async () => {
    const wrapper = await mountView()
    const create = wrapper.findAll('button').find((b) => b.text() === '创建 Webhook')
    await create!.trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith('wf-1', { type: 'WEBHOOK' })
    expect(mocks.list).toHaveBeenCalledTimes(2)
  })

  it('未发布时提示触发会失败', async () => {
    mocks.detail.mockResolvedValue(detail(false))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('还没有发布版本')
  })

  it('停用开关把 enabled=false 发给后端', async () => {
    mocks.list.mockResolvedValue([WEBHOOK])
    const wrapper = await mountView()
    const checkbox = wrapper.find('input[type="checkbox"]')
    await checkbox.setValue(false)
    await flushPromises()
    expect(mocks.patch).toHaveBeenCalledWith('tr-1', { enabled: false })
  })

  it('展示 cron 预览、上次档位与下次触发', async () => {
    mocks.list.mockResolvedValue([SCHEDULE])
    const wrapper = await mountView()

    // 预览来自服务端解析：保存前就能确认「这条 cron 接下来跑在哪」
    expect(wrapper.text()).toContain('接下来')
    expect(wrapper.text()).toContain('2026-11-21 09:00')
    expect(wrapper.text()).toContain('Asia/Shanghai')
    // 上次触发区分「真正跑的时间」与「代表哪一档」
    expect(wrapper.text()).toContain('2026-11-20 09:00 档')
    expect(wrapper.text()).toContain('下次 2026-11-21 09:00')
  })

  it('默认选中「只补最近一档」并允许换成补齐', async () => {
    mocks.list.mockResolvedValue([SCHEDULE])
    mocks.patch.mockResolvedValue(SCHEDULE)
    const wrapper = await mountView()
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios).toHaveLength(3)
    expect((radios[0].element as HTMLInputElement).checked).toBe(true)

    await radios[1].setValue()
    const save = wrapper.findAll('button').find((b) => b.text() === '保存')
    await save!.trigger('click')
    await flushPromises()

    // 策略随 cron 一起保存：用户改的是「这条触发器错过时怎么办」，不是全局设置
    expect(mocks.patch).toHaveBeenCalledWith('tr-2', expect.objectContaining({
      misfirePolicy: 'CATCH_UP_ALL',
    }))
  })

  it('跳过后显示「上次扫描跳过」，不冒充一次触发', async () => {
    mocks.list.mockResolvedValue([{ ...SCHEDULE, lastOutcome: 'SKIP' as const }])
    const wrapper = await mountView()

    // lastFiredAt 原样保留着两天前的时间：跳过没跑，界面就不能说「上次触发」
    expect(wrapper.text()).toContain('上次扫描跳过')
    expect(wrapper.text()).toContain('档（未执行）')
    expect(wrapper.text()).not.toContain('上次触发')
  })

  it('并发上限可改，改的是工作流而不是单条触发器', async () => {
    mocks.list.mockResolvedValue([SCHEDULE])
    const wrapper = await mountView()

    const input = wrapper.find('input[type="number"]')
    expect((input.element as HTMLInputElement).value).toBe('0')
    await input.setValue('3')
    const save = wrapper.findAll('button').find((b) => b.text() === '保存上限')
    await save!.trigger('click')
    await flushPromises()

    // 上限属于整条流水线：定时与 webhook 共用同一个闸门
    expect(mocks.workflowPatch).toHaveBeenCalledWith('wf-1', { maxConcurrent: 3 })
  })

  it('并发上限没改时保存按钮不可点，避免无意义的写请求', async () => {
    mocks.list.mockResolvedValue([SCHEDULE])
    const wrapper = await mountView()

    const save = wrapper.findAll('button').find((b) => b.text() === '保存上限')
    expect((save!.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('预览失败时把后端原因就地显示', async () => {
    mocks.previewCron.mockRejectedValue(new ApiError({
      status: 409,
      title: 'Conflict',
      detail: 'trigger granularity is one minute: cron seconds field must be 0, got */5',
      code: 'WF-STATE-001',
      traceId: 'trace-10',
    } as never))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('这条 cron 存不下来')
    expect(wrapper.text()).toContain('seconds field must be 0')
  })

  it('cron 填错时把后端原因显示出来', async () => {
    mocks.create.mockRejectedValue(new ApiError({
      status: 409,
      title: 'Conflict',
      detail: 'invalid cron expression: 0 9 * * 8',
      code: 'WF-STATE-001',
      traceId: 'trace-9',
    } as never))
    const wrapper = await mountView()
    const create = wrapper.findAll('button').find((b) => b.text() === '创建定时触发')
    await create!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前状态不允许此操作')
    expect(wrapper.text()).toContain('invalid cron expression: 0 9 * * 8')
  })
})
