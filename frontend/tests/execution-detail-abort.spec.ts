import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type * as ApiModule from '@/api'
import type { ExecutionDetail } from '@/types/api'
import ExecutionDetailView from '@/views/ExecutionDetailView.vue'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  events: vi.fn(),
  nodeIo: vi.fn(),
  cancel: vi.fn(),
  workflowDetail: vi.fn(),
  openSse: vi.fn(() => ({ close: vi.fn() })),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    executionApi: {
      ...actual.executionApi,
      detail: mocks.detail,
      events: mocks.events,
      nodeIo: mocks.nodeIo,
      cancel: mocks.cancel,
    },
    workflowApi: {
      ...actual.workflowApi,
      detail: mocks.workflowDetail,
    },
  }
})

vi.mock('@/api/sse', () => ({ openSse: mocks.openSse }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'exec-1' }, fullPath: '/executions/exec-1' }),
  useRouter: () => ({ push: vi.fn() }),
}))

function snapshot(status: string): ExecutionDetail {
  return {
    execution: {
      seq: 1,
      id: 'exec-1',
      definitionId: 'wf-1',
      versionId: 'wfv-1',
      triggeredBy: 'u1',
      status,
      traceId: 'tr-1',
      tokenBudget: 50000,
      maxSteps: 50,
      tokenUsed: 0,
      stepsUsed: 0,
      errorCode: null,
      startedAt: null,
      finishedAt: null,
      createdAt: '2026-11-20T03:28:55',
    },
    nodes: [],
  } as ExecutionDetail
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.events.mockResolvedValue({ events: [], lastSeq: 0 })
  mocks.workflowDetail.mockResolvedValue({ versions: [] })
  mocks.nodeIo.mockResolvedValue({ in: null, out: null })
  mocks.cancel.mockResolvedValue({ ok: true, status: 'CANCELLED' })
})

describe('执行详情切路由后的在途请求', () => {
  it('卸载会 abort 在途 signal，迟到的详情响应不再写状态、不再连 SSE', async () => {
    let resolveDetail!: (v: ExecutionDetail) => void
    mocks.detail.mockReturnValue(new Promise<ExecutionDetail>((r) => { resolveDetail = r }))

    const wrapper = mount(ExecutionDetailView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(mocks.detail).toHaveBeenCalledTimes(1)
    const signal = mocks.detail.mock.calls[0][1] as AbortSignal
    expect(signal).toBeInstanceOf(AbortSignal)
    expect(signal.aborted).toBe(false)

    wrapper.unmount()
    expect(signal.aborted).toBe(true)

    // 卸载后才返回的旧响应：disposed 护栏必须挡住后续所有状态写入与副作用。
    resolveDetail(snapshot('RUNNING'))
    await flushPromises()
    await flushPromises()
    expect(mocks.workflowDetail).not.toHaveBeenCalled()
    expect(mocks.events).not.toHaveBeenCalled()
    expect(mocks.openSse).not.toHaveBeenCalled()
  })

  it('首屏各 REST 请求都携带同一个视图级 signal', async () => {
    mocks.detail.mockResolvedValue(snapshot('RUNNING'))

    const wrapper = mount(ExecutionDetailView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    await flushPromises()

    const signal = mocks.detail.mock.calls[0][1] as AbortSignal
    expect(signal).toBeInstanceOf(AbortSignal)
    // detail / events / workflowApi.detail 用的是同一个 signal，卸载可一次性取消。
    expect(mocks.events.mock.calls[0][3]).toBe(signal)
    expect(mocks.workflowDetail.mock.calls[0][1]).toBe(signal)
    expect(signal.aborted).toBe(false)

    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
})
