<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { workflowApi } from '@/api'
import { ApiError } from '@/api/http'
import { formatInstant } from '@/utils/instant'
import type { WorkflowDefinitionDto } from '@/types/api'

const router = useRouter()
const items = ref<WorkflowDefinitionDto[]>([])
const keyword = ref('')
const status = ref('')
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const creating = ref(false)
const newName = ref('')

async function load(reset = true) {
  loading.value = true
  error.value = null
  try {
    const page = await workflowApi.list({
      status: status.value || undefined,
      keyword: keyword.value || undefined,
      cursor: reset ? undefined : (nextCursor.value ?? undefined),
    })
    items.value = reset ? page.items : [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '加载失败'
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!newName.value.trim()) return
  creating.value = true
  try {
    const { id } = await workflowApi.create(newName.value.trim())
    void router.push({ name: 'editor', params: { id } })
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '创建失败'
  } finally {
    creating.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      工作流
    </h1>

    <div style="display: flex; gap: 8px; margin-bottom: 16px">
      <input
        v-model="keyword"
        class="af-input"
        style="max-width: 260px"
        placeholder="搜索名称/描述"
        @keyup.enter="load()"
      >
      <select
        v-model="status"
        class="af-select"
        style="width: 140px"
        @change="load()"
      >
        <option value="">
          全部状态
        </option>
        <option value="DRAFT">
          DRAFT
        </option>
        <option value="PUBLISHED">
          PUBLISHED
        </option>
        <option value="ARCHIVED">
          ARCHIVED
        </option>
      </select>
      <button
        class="af-btn"
        :disabled="loading"
        @click="load()"
      >
        查询
      </button>
      <div class="af-spacer" />
      <input
        v-model="newName"
        class="af-input"
        style="max-width: 220px"
        placeholder="新工作流名称"
        @keyup.enter="create"
      >
      <button
        class="af-btn af-btn--primary"
        :disabled="creating || !newName.trim()"
        @click="create"
      >
        新建
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
            <th>名称</th>
            <th>状态</th>
            <th>当前版本</th>
            <th>更新时间</th>
            <th style="width: 200px">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="wf in items"
            :key="wf.id"
          >
            <td>
              <router-link :to="{ name: 'editor', params: { id: wf.id } }">{{ wf.name }}</router-link>
              <div
                v-if="wf.description"
                class="af-faint"
                style="font-size: 11px"
              >
                {{ wf.description }}
              </div>
            </td>
            <td>
              <span
                class="af-status"
                :class="{ 'af-status--running': wf.status === 'PUBLISHED' }"
              >
                {{ wf.status }}
              </span>
            </td>
            <td class="mono af-dim">
              {{ wf.currentVersionId ? '已发布' : '—' }}
            </td>
            <td class="mono af-dim">
              {{ formatInstant(wf.updatedAt) }}
            </td>
            <td>
              <router-link :to="{ name: 'editor', params: { id: wf.id } }">
                <button class="af-btn af-btn--sm">画布</button>
              </router-link>
              <router-link :to="{ name: 'versions', params: { id: wf.id } }">
                <button
                  class="af-btn af-btn--sm"
                  style="margin-left: 6px"
                >版本</button>
              </router-link>
              <router-link :to="{ name: 'executions', query: { definitionId: wf.id } }">
                <button
                  class="af-btn af-btn--sm"
                  style="margin-left: 6px"
                >执行</button>
              </router-link>
            </td>
          </tr>
          <tr v-if="!items.length && !loading">
            <td
              colspan="5"
              class="af-faint"
              style="text-align: center; padding: 24px"
            >
              暂无工作流
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
