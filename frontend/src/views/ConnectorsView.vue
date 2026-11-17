<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { connectorApi } from '@/api'
import type { ConnectorAuthType, ConnectorDto } from '@/types/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const connectors = ref<ConnectorDto[]>([])
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)

const form = reactive({
  name: '',
  baseUrl: '',
  allowedHosts: '',
  authType: 'API_KEY' as ConnectorAuthType,
  secret: '',
})

const canManage = computed(() => {
  const role = auth.user?.memberships.find((m) => m.tenantId === auth.currentSpaceId)?.role
  return role === 'OWNER' || role === 'ADMIN'
})

function describe(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : '操作失败'
}

async function load(): Promise<void> {
  try {
    connectors.value = await connectorApi.list()
    error.value = null
  } catch (e) {
    error.value = describe(e)
  }
}

async function create(): Promise<void> {
  busy.value = true
  error.value = null
  notice.value = null
  try {
    const hosts = form.allowedHosts
      .split(',')
      .map((h) => h.trim())
      .filter(Boolean)
    const created = await connectorApi.create({
      name: form.name.trim(),
      type: 'HTTP',
      authType: form.authType,
      config: {
        baseUrl: form.baseUrl.trim(),
        allowedHosts: hosts,
        operations: {},
      },
    })
    if (form.authType !== 'NONE' && form.secret.trim()) {
      await connectorApi.authorize(created.id, form.secret.trim())
    }
    notice.value = `已创建 ${created.name}`
    form.name = ''
    form.baseUrl = ''
    form.allowedHosts = ''
    form.secret = ''
    await load()
  } catch (e) {
    error.value = describe(e)
  } finally {
    busy.value = false
  }
}

async function authorize(row: ConnectorDto): Promise<void> {
  const secret = window.prompt(`为「${row.name}」输入凭据（只以密文存储）`)
  if (!secret) return
  try {
    await connectorApi.authorize(row.id, secret)
    notice.value = `已授权 ${row.name}`
    await load()
  } catch (e) {
    error.value = describe(e)
  }
}

async function toggle(row: ConnectorDto): Promise<void> {
  try {
    await connectorApi.update(row.id, { enabled: !row.enabled })
    await load()
  } catch (e) {
    error.value = describe(e)
  }
}

async function remove(row: ConnectorDto): Promise<void> {
  if (!window.confirm(`删除连接器「${row.name}」？`)) return
  try {
    await connectorApi.remove(row.id)
    await load()
  } catch (e) {
    error.value = describe(e)
  }
}

onMounted(load)
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      连接器
    </h1>
    <p
      class="af-faint"
      style="margin-bottom: 12px; font-size: 12px"
    >
      凭据只以 AES-256-GCM 密文存储；出网受白名单与私网拦截约束（沙箱）。
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

    <div
      v-if="canManage"
      class="af-panel"
      style="margin-bottom: 12px; padding: 12px"
    >
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <label
          style="width: 96px"
          class="af-dim"
        >名称</label>
        <input
          v-model="form.name"
          class="af-input"
          placeholder="crm-search"
        >
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <label
          style="width: 96px"
          class="af-dim"
        >Base URL</label>
        <input
          v-model="form.baseUrl"
          class="af-input"
          placeholder="https://api.example.com"
        >
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <label
          style="width: 96px"
          class="af-dim"
        >允许域名</label>
        <input
          v-model="form.allowedHosts"
          class="af-input"
          placeholder="api.example.com, *.trusted.io"
        >
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <label
          style="width: 96px"
          class="af-dim"
        >鉴权</label>
        <select
          v-model="form.authType"
          class="af-select"
        >
          <option value="NONE">
            无
          </option>
          <option value="API_KEY">
            API Key
          </option>
          <option value="BASIC">
            Basic
          </option>
          <option value="OAUTH2">
            OAuth2
          </option>
        </select>
      </div>
      <div
        v-if="form.authType !== 'NONE'"
        style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px"
      >
        <label
          style="width: 96px"
          class="af-dim"
        >
          {{ form.authType === 'OAUTH2' ? 'Client Secret' : '凭据' }}
        </label>
        <input
          v-model="form.secret"
          class="af-input"
          type="password"
          placeholder="只存密文"
        >
      </div>
      <button
        class="af-btn af-btn--primary af-btn--sm"
        :disabled="busy"
        @click="create"
      >
        新建连接器
      </button>
    </div>

    <div class="af-panel">
      <table class="af-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>归属</th>
            <th>鉴权</th>
            <th>凭据</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="c in connectors"
            :key="c.id"
          >
            <td class="mono">
              {{ c.name }}
            </td>
            <td class="mono af-dim">
              {{ c.inherited ? '继承' : '本空间' }}
            </td>
            <td class="mono">
              {{ c.authType }}
            </td>
            <td
              class="mono"
              :class="c.hasCredential ? '' : 'af-faint'"
            >
              {{ c.hasCredential ? '已配置' : '未配置' }}
            </td>
            <td class="mono">
              {{ c.enabled ? '启用' : '停用' }}
            </td>
            <td>
              <template v-if="!c.inherited && canManage">
                <button
                  class="af-btn af-btn--sm"
                  @click="authorize(c)"
                >
                  授权
                </button>
                <button
                  class="af-btn af-btn--sm"
                  @click="toggle(c)"
                >
                  {{ c.enabled ? '停用' : '启用' }}
                </button>
                <button
                  class="af-btn af-btn--sm"
                  @click="remove(c)"
                >
                  删除
                </button>
              </template>
              <span
                v-else
                class="af-faint"
              >只读</span>
            </td>
          </tr>
          <tr v-if="!connectors.length">
            <td
              colspan="6"
              class="af-faint"
              style="text-align: center; padding: 20px"
            >
              暂无连接器
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
