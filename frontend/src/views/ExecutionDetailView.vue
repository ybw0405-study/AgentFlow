<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { executionApi } from '@/api'
import { openSse } from '@/api/sse'
import type { SseSession } from '@/api/sse'
import { useAuthStore } from '@/stores/auth'
import { errorText } from '@/api/errors'
import type { EngineEvent, ExecutionDetail, NodeExecutionDto, NodeIo } from '@/types/api'
import NodeTree from '@/components/execution/NodeTree.vue'
import IoPanel from '@/components/execution/IoPanel.vue'
import TracePanel from '@/components/execution/TracePanel.vue'

const route = useRoute()
const auth = useAuthStore()
const executionId = route.params.id as string

const detail = ref<ExecutionDetail | null>(null)
const error = ref<string | null>(null)
const selectedKey = ref<string | null>(null)
const io = ref<NodeIo | null>(null)
const ioLoading = ref(false)
const liveNote = ref('')
let sse: SseSession | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
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
  detail.value = await executionApi.detail(executionId)
}

async function selectNode(nodeKey: string): Promise<void> {
  selectedKey.value = nodeKey
  ioLoading.value = true
  io.value = null
  try {
    io.value = await executionApi.nodeIo(executionId, nodeKey)
  } catch {
    io.value = null
  } finally {
    ioLoading.value = false
  }
}

async function cancel(): Promise<void> {
  await executionApi.cancel(executionId)
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
    case 'ExecutionStarted':
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
    const existing = rows.find(
      (n) => n.nodeKey === e.nodeKey && n.attempt === (Number(e.payload.attempt ?? 0)),
    )
    const statusByEvent: Record<string, string> = {
      NodeScheduled: 'SCHEDULED',
      NodeStarted: 'RUNNING',
      NodeCompleted: 'SUCCEEDED',
      NodeFailed: 'FAILED',
      NodeSkipped: 'SKIPPED',
      GateSuspended: 'SUSPENDED',
    }
    const status = statusByEvent[e.eventType]
    if (status && existing) {
      existing.status = status
      if (typeof e.payload.tokenUsed === 'number') existing.tokenUsed = e.payload.tokenUsed
      if (typeof e.payload.latencyMs === 'number') existing.latencyMs = e.payload.latencyMs
      if (typeof e.payload.errorCode === 'string') existing.errorCode = e.payload.errorCode
    } else if (status) {
      rows.push({
        seq: e.seq,
        nodeKey: e.nodeKey,
        displayOrder: e.aggregateVersion,
        status,
        attempt: Number(e.payload.attempt ?? 0),
        outputDigest: null,
        tokenUsed: Number(e.payload.tokenUsed ?? 0),
        latencyMs: null,
        retryCount: 0,
        errorCode: null,
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
    error.value = errorText(e)
    return
  }
  if (TERMINAL.has(detail.value?.execution.status ?? '')) {
    liveNote.value = '执行已结束'
    return
  }
  connectSse()
})

onUnmounted(() => {
  disposed = true
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
         grid-template-columns: 260px 1fr 280px"
    >
      <div
        class="af-panel"
        style="border-radius: 0; border-width: 0 1px 0 0"
      >
        <NodeTree
          :nodes="nodes"
          :selected-key="selectedKey"
          @select="selectNode"
        />
      </div>
      <div
        class="af-panel"
        style="border-radius: 0; border-width: 0 1px 0 0"
      >
        <IoPanel
          :io="io"
          :loading="ioLoading"
          :node-key="selectedKey"
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
