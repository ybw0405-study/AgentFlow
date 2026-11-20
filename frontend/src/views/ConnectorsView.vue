<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { connectorApi } from '@/api'
import type { ConnectorAuthType, ConnectorDto, LlmConnectionConfig } from '@/types/api'
import { errorText } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { buildLlmConfig, hostOf, mergeModels, probeErrorMessage } from '@/dsl/model-connection'

const auth = useAuthStore()
const connectors = ref<ConnectorDto[]>([])
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)

/** 两种连接器共用加密、归属与沙箱，只是引擎用它们的方式不同。 */
type Kind = 'LLM' | 'HTTP'

const form = reactive({
  kind: 'LLM' as Kind,
  name: '',
  baseUrl: '',
  allowedHosts: '',
  models: '',
  defaultModel: '',
  authType: 'API_KEY' as ConnectorAuthType,
  secret: '',
})

const canManage = computed(() => {
  const role = auth.user?.memberships.find((m) => m.tenantId === auth.currentSpaceId)?.role
  return role === 'OWNER' || role === 'ADMIN'
})

function describe(e: unknown): string {
  return errorText(e)
}

/** 用户手填的模型清单（逗号分隔）；与探测结果合并，给上游不支持 /models 的端点兜底。 */
function manualModels(): string[] {
  return form.models
    .split(/[,，\s]+/)
    .map((m) => m.trim())
    .filter(Boolean)
}

/** 与画布「快速接入」共用同一份 config 构造规则，避免两条入口行为漂移。 */
function llmConfig(models: string[], defaultModel: string): Record<string, unknown> {
  return buildLlmConfig({
    baseUrl: form.baseUrl,
    allowedHosts: form.allowedHosts.split(',').map((h) => h.trim()).filter(Boolean),
    models,
    defaultModel,
  })
}

function toolConfig(): Record<string, unknown> {
  return {
    baseUrl: form.baseUrl.trim(),
    allowedHosts: form.allowedHosts
      .split(',')
      .map((h) => h.trim())
      .filter(Boolean),
    operations: {},
  }
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
  const isLlm = form.kind === 'LLM'
  const typed = isLlm ? manualModels() : []
  try {
    if (isLlm && !form.allowedHosts.trim()) {
      form.allowedHosts = hostOf(form.baseUrl.trim())
    }
    const created = await connectorApi.create({
      name: form.name.trim(),
      type: form.kind,
      authType: form.authType,
      config: isLlm ? llmConfig(typed, form.defaultModel.trim()) : toolConfig(),
    })
    if (form.authType !== 'NONE' && form.secret.trim()) {
      await connectorApi.authorize(created.id, form.secret.trim())
    }

    let suffix = ''
    if (isLlm) {
      // 拉取只为省事：上游没有 /models 时，用户手填的清单照样能用
      const probe = form.secret.trim()
        ? await connectorApi.probeModels(created.id)
        : { ok: false, models: [] as string[], errorCode: null }
      const merged = mergeModels(typed, probe.models)
      if (merged.length) {
        const wanted = form.defaultModel.trim()
        const fallback = merged.includes(wanted) ? wanted : merged[0]
        await connectorApi.update(created.id, { config: llmConfig(merged, fallback) })
      }
      if (probe.ok && probe.models.length) {
        suffix = `，可选用 ${merged.length} 个模型`
      } else if (merged.length) {
        suffix = `，已用手填的 ${merged.length} 个模型${probe.errorCode ? `（自动拉取失败：${probeErrorMessage(probe.errorCode)}）` : ''}`
      } else if (probe.errorCode) {
        suffix = `，但${probeErrorMessage(probe.errorCode)}`
      }
    }

    notice.value = `已创建 ${created.name}${suffix}`
    form.name = ''
    form.baseUrl = ''
    form.allowedHosts = ''
    form.models = ''
    form.defaultModel = ''
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

/** 重新拉一次模型清单：换了密钥或端点新增模型后，不必删掉连接重建。 */
async function refreshModels(row: ConnectorDto): Promise<void> {
  try {
    const probe = await connectorApi.probeModels(row.id)
    if (!probe.ok || !probe.models.length) {
      error.value = probeErrorMessage(probe.errorCode)
      return
    }
    const cfg = (row.config ?? {}) as LlmConnectionConfig
    const keep = cfg.defaultModel && probe.models.includes(cfg.defaultModel)
    await connectorApi.update(row.id, {
      config: { ...cfg, models: probe.models, defaultModel: keep ? cfg.defaultModel : probe.models[0] },
    })
    notice.value = `${row.name}：${probe.models.length} 个模型`
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

function modelCount(row: ConnectorDto): number {
  const cfg = (row.config ?? {}) as LlmConnectionConfig
  return Array.isArray(cfg.models) ? cfg.models.length : 0
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
      模型连接建成后即可在画布的 llm 节点里直接选用它的模型。
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
        >类型</label>
        <select
          v-model="form.kind"
          class="af-select"
        >
          <option value="LLM">
            模型接入（OpenAI 兼容）
          </option>
          <option value="HTTP">
            HTTP 工具
          </option>
        </select>
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <label
          style="width: 96px"
          class="af-dim"
        >名称</label>
        <input
          v-model="form.name"
          class="af-input"
          :placeholder="form.kind === 'LLM' ? 'deepseek' : 'crm-search'"
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
          :placeholder="form.kind === 'LLM' ? 'https://api.deepseek.com/v1' : 'https://api.example.com'"
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
          :placeholder="form.kind === 'LLM' ? '留空则取 Base URL 的域名' : 'api.example.com, *.trusted.io'"
        >
      </div>
      <div
        v-if="form.kind === 'LLM'"
        style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px"
      >
        <label
          style="width: 96px"
          class="af-dim"
        >模型</label>
        <input
          v-model="form.models"
          class="af-input mono"
          placeholder="deepseek-chat, deepseek-reasoner（留空则自动拉取）"
        >
      </div>
      <div
        v-if="form.kind === 'LLM'"
        style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px"
      >
        <label
          style="width: 96px"
          class="af-dim"
        >默认模型</label>
        <input
          v-model="form.defaultModel"
          class="af-input mono"
          placeholder="留空则取拉到的第一个"
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
          :placeholder="form.kind === 'LLM' ? 'sk-...（只存密文，不加 Bearer 前缀）' : '只存密文'"
        >
      </div>
      <button
        class="af-btn af-btn--primary af-btn--sm"
        :disabled="busy"
        @click="create"
      >
        {{ form.kind === 'LLM' ? '新建模型连接' : '新建连接器' }}
      </button>
    </div>

    <div class="af-panel">
      <table class="af-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
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
              <div
                v-if="c.type === 'LLM' && modelCount(c)"
                class="af-faint mono"
                style="font-size: 11px"
              >
                {{ modelCount(c) }} 个模型
              </div>
            </td>
            <td class="mono af-dim">
              {{ c.type === 'LLM' ? '模型接入' : 'HTTP' }}
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
                  v-if="c.type === 'LLM'"
                  class="af-btn af-btn--sm"
                  @click="refreshModels(c)"
                >
                  拉取模型
                </button>
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
              colspan="7"
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
