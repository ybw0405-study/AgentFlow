<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { executionApi, workflowApi } from '@/api'
import { openSse } from '@/api/sse'
import type { SseSession } from '@/api/sse'
import { useAuthStore } from '@/stores/auth'
import { errorText } from '@/api/errors'
import type { EngineEvent, ExecutionDetail, NodeExecutionDto, NodeIo } from '@/types/api'
import NodeTree from '@/components/execution/NodeTree.vue'
import IoPanel from '@/components/execution/IoPanel.vue'
import TracePanel from '@/components/execution/TracePanel.vue'
import ExecutionGraph from '@/components/execution/ExecutionGraph.vue'
import type { WorkflowDsl } from '@/dsl/types'

const route = useRoute()
const auth = useAuthStore()
const executionId = route.params.id as string

const detail = ref<ExecutionDetail | null>(null)
/** 执行当时冻结的不可变版本 DSL；图视图据此还原节点与连线。 */
const dsl = ref<WorkflowDsl | null>(null)
const dslLoading = ref(false)
const viewMode = ref<'graph' | 'list'>('graph')
const error = ref<string | null>(null)
const selectedKey = ref<string | null>(null)
const io = ref<NodeIo | null>(null)
const selectedNode = computed<NodeExecutionDto | null>(
  () => nodes.value.find((n) => n.nodeKey === selectedKey.value) ?? null,
)
const ioLoading = ref(false)
const liveNote = ref('')
let sse: SseSession | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
/** 视图级 AbortController：卸载时取消所有在途 REST，旧响应不会再写已卸载组件。 */
const requestController = new AbortController()
const signal = requestController.signal
let reconnectDelay = 1000
/**
 * 已处理事件的最大 seq。SSE 本身保序，但断线重连会从 afterSeq 重新回放，
 * 若不去重，历史 NodeStarted 会把已经 SUCCEEDED 的节点刷回 RUNNING（状态倒退闪烁）。
 */
const lastSeq = ref(0)

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'])

const nodes = computed<NodeExecutionDto[]>(() => detail.value?.nodes ?? [])

/** 右栏「结果」：终态看最终产出，运行中/失败给对应的说明。 */
const resultText = computed<string>(() => {
  const d = detail.value
  if (!d) return ''
  if (d.execution.errorCode) return `执行失败：${d.execution.errorCode}`
  if (d.result === null || d.result === undefined) return '见节点输出（选择左侧节点）'
  if (typeof d.result === 'string') return d.result
  return JSON.stringify(d.result, null, 2)
})

function statusClass(s: string): string {
  return {
    RUNNING: 'af-status--running',
    FAILED: 'af-status--failed',
    TIMED_OUT: 'af-status--timed_out',
  }[s] ?? ''
}

async function load(): Promise<void> {
  const snapshot = await executionApi.detail(executionId, signal)
  if (disposed) return
  detail.value = snapshot
  // 图的 DSL 是附加信息，不能阻塞 SSE 连接：实时态必须按原来的时序建立。
  void loadDsl()
}

/** 必须取执行冻结时的版本，不能用当前草稿，否则图上的节点与执行记录对不上。
 *  取不到时自动回退到节点列表，不让页面留白，也不丢任何执行信息。 */
async function loadDsl(): Promise<void> {
  const exec = detail.value?.execution
  if (!exec) return
  dslLoading.value = true
  try {
    const workflow = await workflowApi.detail(exec.definitionId, signal)
    if (disposed) return
    const version = workflow.versions.find((v) => v.id === exec.versionId)
    dsl.value = version ? (JSON.parse(version.dslJson) as WorkflowDsl) : null
  } catch {
    if (disposed) return
    dsl.value = null
  } finally {
    if (!disposed) dslLoading.value = false
  }
  if (!dsl.value) viewMode.value = 'list'
}

/**
 * 首屏对齐事件游标。
 *
 * REST 快照已经是投影后的最新状态，但 SSE 默认 `afterSeq=0` 会把整条事件流回放一遍：
 * 老的 `ExecutionStarted` 把 SUSPENDED 刷回 RUNNING，不带 attempt 的 `GateSuspended`
 * 又给同一个节点补一条 attempt=0 的重复行。连上之前先问一次当前 lastSeq，SSE 就只收增量。
 * 取不到就退回回放（下面的状态映射仍能兜住），不让实时通道因为这次查询失败而断开。
 */
async function catchUpSeq(): Promise<void> {
  try {
    const page = await executionApi.events(executionId, lastSeq.value, 500, signal)
    if (disposed) return
    if (typeof page.lastSeq === 'number') lastSeq.value = Math.max(lastSeq.value, page.lastSeq)
  } catch {
    // 追平失败不阻断连接
  }
}

async function selectNode(nodeKey: string): Promise<void> {
  selectedKey.value = nodeKey
  ioLoading.value = true
  io.value = null
  try {
    const data = await executionApi.nodeIo(executionId, nodeKey, signal)
    if (disposed) return
    io.value = data
  } catch {
    if (disposed) return
    io.value = null
  } finally {
    if (!disposed) ioLoading.value = false
  }
}

async function cancel(): Promise<void> {
  await executionApi.cancel(executionId, signal)
  if (disposed) return
  await load()
}

function handleLiveEvent(e: EngineEvent): void {
  if (!detail.value) return
  if (typeof e.seq === 'number') {
    if (e.seq <= lastSeq.value) return
    lastSeq.value = e.seq
  }
  const exec = detail.value.execution
  switch (e.eventType) {
    case 'ExecutionCreated':
      exec.status = 'PENDING'
      break
    case 'ExecutionStarted':
      // 万一回放了历史事件，也不能把已经挂起的执行标成运行中
      if (exec.status !== 'SUSPENDED' && !TERMINAL.has(exec.status)) exec.status = 'RUNNING'
      break
    case 'GateSuspended':
      exec.status = 'SUSPENDED'
      break
    case 'GateResolved':
      exec.status = 'RUNNING'
      break
    case 'ExecutionCompleted':
    case 'ExecutionFailed':
    case 'ExecutionCancelled':
    case 'ExecutionTimedOut':
      void load()  // 终态：拉全量对齐投影
      return
    default:
      break
  }
  // 节点事件：轻量更新树（attempt 行以 displayOrder 对齐）
  if (e.nodeKey) {
    const rows = detail.value.nodes
    // GateSuspended / NodeRetryScheduled 这类事件的 payload 里没有 attempt：按 attempt=0
    // 去找会扑空，进而给同一个节点 push 一条重复行。没有 attempt 就按 nodeKey 找最后一行。
    const hasAttempt = typeof e.payload.attempt === 'number'
    const attempt = Number(e.payload.attempt ?? 0)
    const existing = hasAttempt
      ? rows.find((n) => n.nodeKey === e.nodeKey && n.attempt === attempt)
      : [...rows].reverse().find((n) => n.nodeKey === e.nodeKey)
    const statusByEvent: Record<string, string> = {
      NodeScheduled: 'SCHEDULED',
      NodeStarted: 'RUNNING',
      NodeCompleted: 'SUCCEEDED',
      NodeFailed: 'FAILED',
      NodeSkipped: 'SKIPPED',
      NodeSuspended: 'SUSPENDED',
      GateSuspended: 'SUSPENDED',
    }
    const status = statusByEvent[e.eventType]
    if (status && existing) {
      // 回放的历史事件不能让终态或挂起态倒退；只有裁决续跑后的 NodeStarted 才允许
      // 把 SUSPENDED 推回 RUNNING（此时前面必有 GateResolved 把执行状态改回 RUNNING）。
      const replayDowngrade =
        (TERMINAL.has(existing.status) || existing.status === 'SKIPPED')
        || (existing.status === 'SUSPENDED' && status !== 'SUSPENDED' && exec.status !== 'RUNNING')
      if (!replayDowngrade) existing.status = status
      if (typeof e.payload.tokenUsed === 'number') existing.tokenUsed = e.payload.tokenUsed
      if (typeof e.payload.latencyMs === 'number') existing.latencyMs = e.payload.latencyMs
      if (typeof e.payload.errorCode === 'string') existing.errorCode = e.payload.errorCode
      if (typeof e.payload.message === 'string') existing.errorMessage = e.payload.message
      // 实际下发的模型在 output 里（LLM 节点）；投影落库前也要能显示，否则要刷新才看到
      const output = e.payload.output
      if (output && typeof output === 'object' && typeof (output as { model?: unknown }).model === 'string') {
        existing.model = (output as { model: string }).model
      }
    } else if (status) {
      rows.push({
        seq: e.seq,
        nodeKey: e.nodeKey,
        displayOrder: e.aggregateVersion,
        status,
        attempt,
        outputDigest: null,
        tokenUsed: Number(e.payload.tokenUsed ?? 0),
        latencyMs: null,
        model: null,
        retryCount: 0,
        errorCode: null,
        errorMessage: null,
        startedAt: null,
        finishedAt: null,
      } as NodeExecutionDto)
    }
  }
}

/** 建立 SSE；断线时先拉 REST 追平，再按退避自动重连（终态/卸载后停止）。 */
function connectSse(): void {
  if (disposed || TERMINAL.has(detail.value?.execution.status ?? '')) return
  const headers: Record<string, string> = {}
  if (auth.accessToken) headers['Authorization'] = `Bearer ${auth.accessToken}`
  if (auth.currentSpaceId) headers['X-Space-Id'] = auth.currentSpaceId
  // 带上已处理位置：重连只补增量，不必每次回放整条事件流
  const url = `/api/v1/executions/${executionId}/events/stream?afterSeq=${lastSeq.value}`
  sse = openSse(url, { headers }, {
    onFrame: (frame) => {
      if (disposed) return
      // 收到任意帧说明链路已恢复：复位退避，避免网络闪断一次后永远等 15s。
      reconnectDelay = 1000
      if (frame.event === 'engine-event') {
        try {
          handleLiveEvent(JSON.parse(frame.data) as EngineEvent)
        } catch {
          // 忽略坏帧
        }
      }
    },
    onError: () => {
      sse?.close()
      sse = null
      if (disposed || TERMINAL.has(detail.value?.execution.status ?? '')) return
      liveNote.value = `实时通道断开，${reconnectDelay / 1000}s 后重连`
      // 断线先追平 REST，避免重连间隔内的事件缺口；随后退避重连
      void load().catch(() => {})
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null
        reconnectDelay = Math.min(reconnectDelay * 2, 15000)
        connectSse()
      }, reconnectDelay)
    },
  })
  liveNote.value = '实时推送中'
}

onMounted(async () => {
  try {
    await load()
  } catch (e) {
    if (disposed) return
    error.value = errorText(e)
    return
  }
  if (disposed) return
  if (TERMINAL.has(detail.value?.execution.status ?? '')) {
    liveNote.value = '执行已结束'
    return
  }
  await catchUpSeq()
  if (disposed) return
  connectSse()
})

onUnmounted(() => {
  disposed = true
  requestController.abort()
  if (reconnectTimer !== null) clearTimeout(reconnectTimer)
  sse?.close()
  sse = null
})
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%">
    <div class="af-topbar">
      <router-link
        to="/executions"
        class="af-dim"
      >← 执行列表</router-link>
      <span
        v-if="detail"
        class="af-status"
        :class="statusClass(detail.execution.status)"
      >
        {{ detail.execution.status }}
      </span>
      <span class="mono af-dim">{{ executionId.slice(0, 8) }}</span>
      <span
        class="af-faint"
        style="font-size: 11px"
      >{{ liveNote }}</span>
      <div class="af-spacer" />
      <button
        v-if="detail && !TERMINAL.has(detail.execution.status)"
        class="af-btn af-btn--sm af-btn--danger"
        @click="cancel"
      >
        取消执行
      </button>
    </div>

    <div
      v-if="error"
      class="af-error-box"
      style="margin: 8px 12px 0"
    >
      {{ error }}
    </div>

    <div
      v-if="detail"
      style="flex: 1; display: grid; min-height: 0;
         grid-template-columns: minmax(0, 1fr) 340px 280px"
    >
      <div
        class="af-panel"
        style="border-radius: 0; border-width: 0 1px 0 0; display: flex; flex-direction: column; min-width: 0"
      >
        <div style="display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-bottom: 1px solid var(--af-border)">
          <button
            class="af-btn af-btn--sm"
            :class="{ 'af-btn--primary': viewMode === 'graph' }"
            @click="viewMode = 'graph'"
          >
            执行图
          </button>
          <button
            class="af-btn af-btn--sm"
            :class="{ 'af-btn--primary': viewMode === 'list' }"
            @click="viewMode = 'list'"
          >
            节点列表
          </button>
          <span
            class="af-faint mono"
            style="margin-left: auto; font-size: 11px"
          >{{ nodes.length }} 个节点</span>
        </div>
        <div style="flex: 1; min-height: 0">
          <ExecutionGraph
            v-if="viewMode === 'graph' && dsl"
            :dsl="dsl"
            :nodes="nodes"
            :selected-key="selectedKey"
            @select="selectNode"
          />
          <div
            v-else-if="viewMode === 'graph' && dslLoading"
            class="af-faint"
            style="padding: 16px; font-size: 12px"
          >
            正在载入该版本的工作流图…
          </div>
          <div
            v-else-if="viewMode === 'graph'"
            class="af-faint"
            style="padding: 16px; font-size: 12px"
          >
            该版本的 DSL 不可用，已切到节点列表；执行记录完整保留。
          </div>
          <NodeTree
            v-else
            :nodes="nodes"
            :selected-key="selectedKey"
            @select="selectNode"
          />
        </div>
      </div>
      <div
        class="af-panel"
        style="border-radius: 0; border-width: 0 1px 0 0"
      >
        <IoPanel
          :io="io"
          :loading="ioLoading"
          :node-key="selectedKey"
          :node="selectedNode"
        />
      </div>
      <div style="overflow: auto">
        <TracePanel :execution="detail.execution" />
        <div style="padding: 12px">
          <div
            class="af-label"
            style="margin-top: 0"
          >
            结果
          </div>
          <pre
            class="mono af-dim"
            style="white-space: pre-wrap; font-size: 11.5px"
          >{{ resultText }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>
