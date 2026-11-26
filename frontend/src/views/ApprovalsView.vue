<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ConsoleMasthead from '@/components/design/ConsoleMasthead.vue'
import { gateApi } from '@/api'
import { errorText } from '@/api/errors'
import type { GateTaskDto } from '@/types/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const gates = ref<GateTaskDto[]>([])
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)
const comment = ref('')

const canApprove = computed(() => {
  const role = auth.user?.memberships.find((m) => m.tenantId === auth.currentSpaceId)?.role
  return role !== 'VIEWER' && role !== undefined
})

function describe(e: unknown): string {
  return errorText(e)
}

async function load(): Promise<void> {
  try {
    gates.value = await gateApi.inbox()
    error.value = null
  } catch (e) {
    error.value = describe(e)
  }
}

async function resolve(gate: GateTaskDto, decision: 'APPROVED' | 'REJECTED'): Promise<void> {
  busy.value = true
  error.value = null
  notice.value = null
  try {
    const result = await gateApi.resolve(gate.executionId, gate.nodeKey, decision, comment.value)
    notice.value = result.deferred
      ? '裁决已受理（引擎暂不可达，将自动重投）'
      : `已${decision === 'APPROVED' ? '通过' : '驳回'}，执行继续（分支 ${result.branch ?? '-'}）`
    comment.value = ''
    await load()
  } catch (e) {
    error.value = describe(e)
  } finally {
    busy.value = false
  }
}

function countdown(iso?: string): string {
  if (!iso) return '—'
  const left = new Date(iso).getTime() - Date.now()
  if (left <= 0) return '已超时'
  const hours = Math.floor(left / 3_600_000)
  if (hours >= 24) return `${Math.floor(hours / 24)} 天`
  if (hours >= 1) return `${hours} 小时`
  return `${Math.max(1, Math.floor(left / 60_000))} 分钟`
}

onMounted(load)
</script>

<template>
  <div class="af-page">
    <ConsoleMasthead
      no="02"
      code="HUMAN GATE"
      title="审批"
      zh="人工闸门"
      icon="human"
      aside="高风险动作停在人这一侧；超时动作与审批人规则都写进可追溯记录。"
    />
    <p
      class="af-faint"
      style="margin-bottom: 12px; font-size: 12px"
    >
      审批人由挂起瞬间的角色快照决定；超时按 DSL 的 onTimeout 自动裁决，不经人工。
    </p>

    <div
      v-if="error"
      class="af-error-box"
      style="margin-bottom: 12px"
    >
      {{ error }}
    </div>
    <div
      v-if="notice"
      class="af-panel"
      style="margin-bottom: 12px; padding: 10px"
    >
      {{ notice }}
    </div>

    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <input
        v-model="comment"
        class="af-input"
        style="max-width: 360px"
        placeholder="审批意见（可选）"
      >
      <button
        class="af-btn"
        :disabled="busy"
        @click="load"
      >
        刷新
      </button>
    </div>

    <div class="af-panel">
      <table class="af-table">
        <thead>
          <tr>
            <th>待办提示</th>
            <th>执行</th>
            <th>节点</th>
            <th>剩余</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="gate in gates"
            :key="gate.id"
          >
            <td style="max-width: 420px; white-space: pre-wrap">
              {{ gate.prompt }}
            </td>
            <td class="mono">
              <router-link :to="`/executions/${gate.executionId}`">
                {{ gate.executionId.slice(0, 8) }}
              </router-link>
            </td>
            <td class="mono">
              {{ gate.nodeKey }}
            </td>
            <td>{{ countdown(gate.timeoutAt) }}</td>
            <td>
              <div style="display: flex; gap: 6px">
                <button
                  class="af-btn af-btn--primary af-btn--sm"
                  :disabled="busy || !canApprove"
                  @click="resolve(gate, 'APPROVED')"
                >
                  通过
                </button>
                <button
                  class="af-btn af-btn--sm"
                  :disabled="busy || !canApprove"
                  @click="resolve(gate, 'REJECTED')"
                >
                  驳回
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!gates.length">
            <td
              colspan="5"
              class="af-faint"
            >
              没有待审批的节点。
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
