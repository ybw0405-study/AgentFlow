<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { executionApi } from '@/api'
import { errorText } from '@/api/errors'
import type { ExecutionDto } from '@/types/api'

const props = defineProps<{ definitionId?: string }>()

const items = ref<ExecutionDto[]>([])
const status = ref('')
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load(reset = true): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const page = await executionApi.list({
      status: status.value || undefined,
      definitionId: props.definitionId,
      cursor: reset ? undefined : (nextCursor.value ?? undefined),
    })
    items.value = reset ? page.items : [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function statusClass(s: string): string {
  return {
    RUNNING: 'af-status--running',
    FAILED: 'af-status--failed',
    TIMED_OUT: 'af-status--timed_out',
  }[s] ?? ''
}

onMounted(() => load())
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      执行
    </h1>
    <div style="display: flex; gap: 8px; margin-bottom: 16px">
      <select
        v-model="status"
        class="af-select"
        style="width: 160px"
        @change="load()"
      >
        <option value="">
          全部状态
        </option>
        <option
          v-for="s in ['PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT']"
          :key="s"
          :value="s"
        >
          {{ s }}
        </option>
      </select>
      <button
        class="af-btn"
        :disabled="loading"
        @click="load()"
      >
        刷新
      </button>
    </div>

    <div
      v-if="error"
      class="af-error-box"
      style="margin-bottom: 12px"
    >
      {{ error }}
    </div>

    <div class="af-panel">
      <table class="af-table">
        <thead>
          <tr>
            <th>executionId</th><th>状态</th><th>token</th><th>步数</th>
            <th>错误</th><th>开始</th><th>结束</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="ex in items"
            :key="ex.id"
            style="cursor: pointer"
            @click="$router.push({ name: 'execution-detail', params: { id: ex.id } })"
          >
            <td class="mono af-accent">
              {{ ex.id.slice(0, 8) }}
            </td>
            <td>
              <span
                class="af-status"
                :class="statusClass(ex.status)"
              >{{ ex.status }}</span>
            </td>
            <td class="mono">
              {{ ex.tokenUsed }}
            </td>
            <td class="mono">
              {{ ex.stepsUsed }}
            </td>
            <td class="mono af-danger">
              {{ ex.errorCode ?? '' }}
            </td>
            <td class="mono af-dim">
              {{ ex.startedAt?.slice(0, 19) ?? '—' }}
            </td>
            <td class="mono af-dim">
              {{ ex.finishedAt?.slice(0, 19) ?? '—' }}
            </td>
          </tr>
          <tr v-if="!items.length && !loading">
            <td
              colspan="7"
              class="af-faint"
              style="text-align: center; padding: 24px"
            >
              暂无执行
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="nextCursor"
      style="margin-top: 12px"
    >
      <button
        class="af-btn"
        :disabled="loading"
        @click="load(false)"
      >
        加载更多
      </button>
    </div>
  </div>
</template>
