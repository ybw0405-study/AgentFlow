import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type * as ApiModule from '@/api'
import type { ExecutionDetail, ExecutionDto, NodeExecutionDto } from '@/types/api'
import ExecutionDetailView from '@/views/ExecutionDetailView.vue'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  events: vi.fn(),
  nodeIo: vi.fn(),
  handlers: null as null | { onFrame: (f: { event: string; data: string }) => void },
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
      cancel: vi.fn(),
    },
    workflowApi: {
      ...actual.workflowApi,
      // 该用例聚焦 SSE 实时合并：没有版本 DSL 时页面回退到节点列表，断言保持不变。
      detail: vi.fn(async () => ({ definition: {}, versions: [] })),
    },
  }
})

vi.mock('@/api/sse', () => ({
  openSse: vi.fn((_url: string, _opts: unknown, h: typeof mocks.handlers) => {
    mocks.handlers = h
    return { close: vi.fn() }
  }),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'ce79f253-5d00-4ffa-b760-b6065223c685' } }),
}))

function node(status: string): NodeExecutionDto {
  return {
    seq: 25,
    nodeKey: 'review',
    displayOrder: 9,
    status,
    attempt: 1,
    outputDigest: null,
    tokenUsed: 0,
    latencyMs: null,
    retryCount: 0,
    errorCode: null,
    errorMessage: null,
    startedAt: null,
    finishedAt: null,
  }
}

function snapshot(status: ExecutionDto['status'], nodeStatus: string): ExecutionDetail {
  return {
    execution: {
      seq: 1,
      id: 'ce79f253-5d00-4ffa-b760-b6065223c685',
      definitionId: 'wf-demo-triage',
      versionId: 'wfv-demo-triage-1',
      triggeredBy: 'u-admin',
      status,
      traceId: 'affdeb66',
      tokenBudget: 50000,
      maxSteps: 50,
      tokenUsed: 0,
      stepsUsed: 0,
      errorCode: null,
      startedAt: '2026-11-20T03:28:55',
      finishedAt: null,
      createdAt: '2026-11-20T03:28:55',
    },
    nodes: [node(nodeStatus)],
  }
}

function frame(seq: number, eventType: string, payload: Record<string, unknown> = {}) {
  return {
    event: 'engine-event',
    data: JSON.stringify({
      seq,
      eventType,
      nodeKey: 'review',
      payload,
      aggregateVersion: seq,
    }),
  }
}

async function mountView(status: ExecutionDto['status'], nodeStatus: string, lastSeq: number) {
  mocks.detail.mockResolvedValue(snapshot(status, nodeStatus))
  mocks.events.mockResolvedValue({ events: [], lastSeq })
  mocks.nodeIo.mockResolvedValue({ in: null, out: null })
  const wrapper = mount(ExecutionDetailView, {
    global: { plugins: [createPinia()], stubs: { 'router-link': true } },
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mocks.handlers = null
  vi.clearAllMocks()
})

describe('执行详情的实时事件合并', () => {
  it('首屏按当前游标连 SSE，历史回放不会把 SUSPENDED 刷回 RUNNING 或补重复行', async () => {
    const wrapper = await mountView('SUSPENDED', 'SUSPENDED', 41)
    expect(wrapper.find('.af-topbar .af-status').text()).toBe('SUSPENDED')
    expect(mocks.handlers).not.toBeNull()
    mocks.handlers?.onFrame(frame(1, 'ExecutionStarted'))
    mocks.handlers?.onFrame(frame(40, 'GateSuspended'))
    await flushPromises()
    expect(wrapper.find('.af-topbar .af-status').text()).toBe('SUSPENDED')
    expect(wrapper.findAll('.af-tree__row')).toHaveLength(1)
  })

  it('实时 GateSuspended 把执行与节点都标成挂起，且不带 attempt 也不重复建行', async () => {
    const wrapper = await mountView('RUNNING', 'RUNNING', 38)
    mocks.handlers?.onFrame(frame(39, 'NodeSuspended', { attempt: 1 }))
    mocks.handlers?.onFrame(frame(40, 'GateSuspended'))
    await flushPromises()
    expect(wrapper.find('.af-topbar .af-status').text()).toBe('SUSPENDED')
    expect(wrapper.findAll('.af-tree__row')).toHaveLength(1)
    expect(wrapper.find('.af-tree__row .af-status').text()).toBe('SUSPENDED')
  })

  it('NodeCompleted 带回实际下发模型时，节点树上直接就显示', async () => {
    const wrapper = await mountView('RUNNING', 'RUNNING', 38)

    mocks.handlers?.onFrame(frame(39, 'NodeCompleted', {
      attempt: 1,
      tokenUsed: 120,
      output: { model: 'deepseek-v4-pro', text: 'ok' },
    }))
    await flushPromises()

    // 投影落库有 500ms 的间隔，实时事件里已经带着模型了，不该要用户刷新才看到
    expect(wrapper.find('.af-tree__row').text()).toContain('deepseek-v4-pro')
  })

  it('裁决续跑后节点能从 SUSPENDED 回到 RUNNING', async () => {
    const wrapper = await mountView('SUSPENDED', 'SUSPENDED', 40)
    mocks.handlers?.onFrame(frame(42, 'GateResolved'))
    mocks.handlers?.onFrame(frame(43, 'NodeStarted', { attempt: 1 }))
    await flushPromises()
    expect(wrapper.find('.af-topbar .af-status').text()).toBe('RUNNING')
    expect(wrapper.find('.af-tree__row .af-status').text()).toBe('RUNNING')
  })
})
