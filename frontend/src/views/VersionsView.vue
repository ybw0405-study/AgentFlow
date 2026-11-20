<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { workflowApi } from '@/api'
import { errorText } from '@/api/errors'
import { formatInstant } from '@/utils/instant'
import type { WorkflowDefinitionDto, WorkflowVersionDto } from '@/types/api'

const route = useRoute()
const workflowId = route.params.id as string

const def = ref<WorkflowDefinitionDto | null>(null)
const versions = ref<WorkflowVersionDto[]>([])
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)
const diffA = ref<string | null>(null)
const diffB = ref<string | null>(null)

async function load(): Promise<void> {
  const detail = await workflowApi.detail(workflowId)
  def.value = detail.definition
  versions.value = detail.versions
}

async function freeze(): Promise<void> {
  busy.value = true
  error.value = null
  try {
    await workflowApi.freeze(workflowId)
    notice.value = '已冻结新版本'
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function publish(versionId: string): Promise<void> {
  busy.value = true
  error.value = null
  try {
    await workflowApi.publish(workflowId, versionId)
    notice.value = '已发布'
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function rollback(versionId: string): Promise<void> {
  busy.value = true
  error.value = null
  try {
    await workflowApi.rollback(workflowId, versionId)
    notice.value = '已回滚'
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function pretty(dslJson: string): string {
  try {
    return JSON.stringify(JSON.parse(dslJson), null, 2)
  } catch {
    return dslJson
  }
}

function toggleDiff(versionId: string, side: 'a' | 'b'): void {
  if (side === 'a') diffA.value = diffA.value === versionId ? null : versionId
  else diffB.value = diffB.value === versionId ? null : versionId
}

function diffSource(versionId: string | null): string {
  const v = versions.value.find((x) => x.id === versionId)
  return v ? pretty(v.dslJson) : ''
}

onMounted(() => load().catch((e) => {
  error.value = errorText(e)
}))
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      版本管理 · {{ def?.name }}
    </h1>
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
      style="margin-bottom: 12px; padding: 8px 12px; border-color: var(--af-accent-border)"
    >
      <span class="af-accent">{{ notice }}</span>
    </div>

    <div style="margin-bottom: 12px">
      <button
        class="af-btn"
        :disabled="busy"
        @click="freeze"
      >
        冻结当前草稿为新版本
      </button>
    </div>

    <div class="af-panel">
      <table class="af-table">
        <thead>
          <tr>
            <th>版本</th><th>checksum</th><th>发布于</th><th>发布人</th><th>当前</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="v in versions"
            :key="v.id"
          >
            <td class="mono">
              v{{ v.versionNo }}
            </td>
            <td class="mono af-faint">
              {{ v.dslChecksum.slice(0, 12) }}
            </td>
            <td class="mono af-dim">
              {{ formatInstant(v.publishedAt) }}
            </td>
            <td class="mono af-dim">
              {{ v.publishedBy?.slice(0, 8) ?? '—' }}
            </td>
            <td>
              <span
                v-if="v.id === def?.currentVersionId"
                class="af-status af-status--running"
              >
                CURRENT
              </span>
            </td>
            <td>
              <button
                v-if="v.id !== def?.currentVersionId"
                class="af-btn af-btn--sm"
                :disabled="busy"
                @click="publish(v.id)"
              >
                发布
              </button>
              <button
                v-if="v.id !== def?.currentVersionId"
                class="af-btn af-btn--sm"
                style="margin-left: 6px"
                :disabled="busy"
                @click="rollback(v.id)"
              >
                回滚到此
              </button>
              <button
                class="af-btn af-btn--sm"
                style="margin-left: 6px"
                @click="toggleDiff(v.id, 'a')"
              >
                A
              </button>
              <button
                class="af-btn af-btn--sm"
                style="margin-left: 4px"
                @click="toggleDiff(v.id, 'b')"
              >
                B
              </button>
            </td>
          </tr>
          <tr v-if="!versions.length">
            <td
              colspan="6"
              class="af-faint"
              style="text-align: center; padding: 20px"
            >
              尚无冻结版本
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="diffA && diffB"
      style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 16px"
    >
      <div
        class="af-panel"
        style="padding: 12px"
      >
        <div
          class="af-label"
          style="margin-top: 0"
        >
          A · v{{ versions.find(v => v.id === diffA)?.versionNo }}
        </div>
        <pre
          class="mono af-dim"
          style="white-space: pre-wrap; font-size: 11px"
        >{{ diffSource(diffA) }}</pre>
      </div>
      <div
        class="af-panel"
        style="padding: 12px"
      >
        <div
          class="af-label"
          style="margin-top: 0"
        >
          B · v{{ versions.find(v => v.id === diffB)?.versionNo }}
        </div>
        <pre
          class="mono af-dim"
          style="white-space: pre-wrap; font-size: 11px"
        >{{ diffSource(diffB) }}</pre>
      </div>
    </div>
  </div>
</template>
