<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { triggerApi, workflowApi } from '@/api'
import { describeError, type DescribedError } from '@/api/errors'
import { initialVariables } from '@/dsl/run-defaults'
import RunVariablesForm from '@/components/canvas/RunVariablesForm.vue'
import type { VariableSpec, WorkflowDsl } from '@/dsl/types'
import type { CronPreviewDto, TriggerDto, WorkflowDefinitionDto } from '@/types/api'

const route = useRoute()
const workflowId = route.params.id as string

const def = ref<WorkflowDefinitionDto | null>(null)
const triggers = ref<TriggerDto[]>([])
const dslVariables = ref<Record<string, VariableSpec>>({})
const error = ref<DescribedError | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)
const showSecret = ref(false)
const cronDraft = ref('0 9 * * *')
const scheduleValues = ref<Record<string, unknown>>({})
const preview = ref<CronPreviewDto | null>(null)
const previewError = ref<string | null>(null)
let previewTimer: ReturnType<typeof setTimeout> | undefined

const webhook = computed(() => triggers.value.find((t) => t.type === 'WEBHOOK') ?? null)
const schedule = computed(() => triggers.value.find((t) => t.type === 'SCHEDULE') ?? null)
const hookUrl = computed(() =>
  webhook.value ? `${window.location.origin}/api/v1/hooks/${webhook.value.token}` : '',
)
const published = computed(() => Boolean(def.value?.currentVersionId))

const CRON_PRESETS = [
  { label: '每小时整点', value: '0 * * * *' },
  { label: '每天 09:00', value: '0 9 * * *' },
  { label: '每周一 09:00', value: '0 9 * * 1' },
  { label: '每 15 分钟', value: '*/15 * * * *' },
]

async function load(): Promise<void> {
  const detail = await workflowApi.detail(workflowId)
  def.value = detail.definition
  const text = detail.definition.draftDsl
    ?? detail.versions.find((v) => v.id === detail.definition.currentVersionId)?.dslJson
  if (text) {
    const dsl = JSON.parse(text) as WorkflowDsl
    dslVariables.value = dsl.variables ?? {}
  }
  triggers.value = await triggerApi.list(workflowId)
  if (schedule.value?.cron) cronDraft.value = schedule.value.cron
  scheduleValues.value = schedule.value
    ? { ...initialVariables(dslVariables.value), ...schedule.value.variables }
    : initialVariables(dslVariables.value)
  await loadPreview()
}

/** 「上次触发」是真实瞬间，按浏览器时区渲染；档位与下次是 cron 墙钟，原样展示。 */
function instantText(iso: string | null): string {
  if (!iso) return '从未'
  const date = new Date(iso)
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString('zh-CN', { hour12: false })
}

/**
 * 保存前先解析一遍：cron 写错了在这一步就能看到原因，而不是等创建后报 409。
 * 输入停顿 350ms 才请求，避免边打字边打后端。
 */
function schedulePreview(): void {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(() => {
    void loadPreview()
  }, 350)
}

async function loadPreview(): Promise<void> {
  const cron = cronDraft.value.trim()
  if (!cron) {
    preview.value = null
    previewError.value = null
    return
  }
  try {
    preview.value = await triggerApi.previewCron(workflowId, { cron, count: 3 })
    previewError.value = null
  } catch (e) {
    const described = describeError(e)
    preview.value = null
    previewError.value = described.detail ?? described.text
  }
}

watch(cronDraft, schedulePreview)

async function run<T>(action: () => Promise<T>, message: string): Promise<void> {
  busy.value = true
  error.value = null
  notice.value = null
  try {
    await action()
    notice.value = message
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

function createWebhook(): void {
  void run(() => triggerApi.create(workflowId, { type: 'WEBHOOK' }), 'Webhook 已创建，复制链接即可接入')
}

function createSchedule(): void {
  void run(
    () => triggerApi.create(workflowId, {
      type: 'SCHEDULE',
      cron: cronDraft.value,
      variables: scheduleValues.value,
    }),
    '定时触发已创建',
  )
}

function saveSchedule(): void {
  const current = schedule.value
  if (!current) return
  void run(
    () => triggerApi.patch(current.id, {
      cron: cronDraft.value,
      variables: scheduleValues.value,
    }),
    '定时触发已更新',
  )
}

function toggle(item: TriggerDto, enabled: boolean): void {
  void run(
    () => triggerApi.patch(item.id, { enabled }),
    enabled ? '已启用' : '已停用',
  )
}

function rotate(): void {
  const current = webhook.value
  if (!current) return
  if (!window.confirm('轮换后旧链接立即失效，确认继续？')) return
  void run(() => triggerApi.rotate(current.id), '已轮换令牌，请更新调用方')
}

function remove(item: TriggerDto): void {
  if (!window.confirm('删除后该入口立即失效，确认继续？')) return
  void run(() => triggerApi.remove(item.id), '已删除')
}

async function copy(text: string, label: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    notice.value = `${label}已复制`
  } catch {
    notice.value = '浏览器不允许读取剪贴板，请手动选中复制'
  }
}

onMounted(() => {
  load().catch((e) => {
    error.value = describeError(e)
  })
})
</script>

<template>
  <div class="af-page">
    <div style="display: flex; align-items: baseline; gap: 10px">
      <h1
        class="af-page-title"
        style="margin: 0"
      >
        触发 · {{ def?.name ?? '…' }}
      </h1>
      <router-link
        :to="{ name: 'editor', params: { id: workflowId } }"
        class="af-dim"
      >
        ← 回到画布
      </router-link>
    </div>
    <p
      class="af-faint"
      style="margin: 6px 0 14px; font-size: 12px; line-height: 1.7"
    >
      触发执行的是<b>已发布版本</b>，和画布上的草稿无关：改完流程记得先「冻结并发布」。
      入参里的固定字段写在触发配置里，webhook 请求体里的同名字段会覆盖它。
    </p>

    <div
      v-if="error"
      class="af-error-box"
      style="margin-bottom: 12px"
    >
      <div>{{ error.hint ? `${error.text}，${error.hint}` : error.text }}</div>
      <div
        v-if="error.detail"
        class="af-faint"
        style="margin-top: 4px; font-size: 11px; line-height: 1.6"
      >
        后端说明：{{ error.detail }}
      </div>
    </div>
    <div
      v-if="notice"
      class="af-panel"
      style="margin-bottom: 12px; padding: 8px 12px; border-color: var(--af-accent-border)"
    >
      <span class="af-accent">{{ notice }}</span>
    </div>
    <div
      v-if="!published"
      class="af-error-box"
      style="margin-bottom: 12px; line-height: 1.6"
    >
      这条工作流还没有发布版本，触发会以 409 失败；先在画布上「冻结并发布」。
    </div>

    <div
      class="af-panel"
      style="padding: 14px; margin-bottom: 14px"
    >
      <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 8px">
        <strong>Webhook</strong>
        <span
          class="af-faint"
          style="font-size: 12px"
        >外部系统 POST 一次就触发一轮执行</span>
        <div class="af-spacer" />
        <button
          v-if="!webhook"
          class="af-btn af-btn--sm af-btn--primary"
          :disabled="busy"
          @click="createWebhook"
        >
          创建 Webhook
        </button>
      </div>

      <template v-if="webhook">
        <label class="af-label">入口地址</label>
        <div style="display: flex; gap: 6px; margin-bottom: 8px">
          <input
            class="af-input mono"
            :value="hookUrl"
            readonly
          >
          <button
            class="af-btn af-btn--sm"
            @click="copy(hookUrl, '链接')"
          >
            复制
          </button>
        </div>

        <label class="af-label">签名密钥（X-AF-Signature）</label>
        <div style="display: flex; gap: 6px; margin-bottom: 6px">
          <input
            class="af-input mono"
            :type="showSecret ? 'text' : 'password'"
            :value="webhook.secret"
            readonly
          >
          <button
            class="af-btn af-btn--sm"
            @click="showSecret = !showSecret"
          >
            {{ showSecret ? '隐藏' : '显示' }}
          </button>
          <button
            class="af-btn af-btn--sm"
            @click="copy(webhook.secret, '密钥')"
          >
            复制
          </button>
        </div>
        <p
          class="af-faint"
          style="margin: 0 0 10px; font-size: 11px; line-height: 1.7"
        >
          签名可选：带上 <code>X-AF-Signature: sha256=HMAC_SHA256(secret, 原始请求体)</code>
          就会校验，建议同时带 <code>X-AF-Timestamp</code>（秒）防止重放；
          幂等用 <code>X-AF-Idempotency-Key</code>，重复投递不会重复执行。
        </p>

        <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
          <label
            class="af-faint"
            style="display: flex; gap: 4px; align-items: center; font-size: 12px"
          >
            <input
              type="checkbox"
              :checked="webhook.enabled"
              :disabled="busy"
              @change="toggle(webhook, ($event.target as HTMLInputElement).checked)"
            >
            启用
          </label>
          <span
            class="mono af-faint"
            style="font-size: 11px"
          >
            创建于 {{ webhook.createdAt?.slice(0, 19) ?? '—' }}
          </span>
          <div class="af-spacer" />
          <button
            class="af-btn af-btn--sm"
            :disabled="busy"
            @click="rotate"
          >
            轮换令牌
          </button>
          <button
            class="af-btn af-btn--sm af-btn--danger"
            :disabled="busy"
            @click="remove(webhook)"
          >
            删除
          </button>
        </div>
      </template>
    </div>

    <div
      class="af-panel"
      style="padding: 14px"
    >
      <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 8px">
        <strong>定时</strong>
        <span
          class="af-faint"
          style="font-size: 12px"
        >按 cron 自动跑，精度到分钟；停机恢复后补跑最近一档（只看 24 小时内）</span>
      </div>

      <label class="af-label">cron（5 段：分 时 日 月 周）</label>
      <div style="display: flex; gap: 6px; margin-bottom: 6px">
        <input
          v-model="cronDraft"
          class="af-input mono"
          placeholder="0 9 * * *"
        >
        <button
          v-for="preset in CRON_PRESETS"
          :key="preset.value"
          class="af-btn af-btn--sm"
          :title="preset.value"
          @click="cronDraft = preset.value"
        >
          {{ preset.label }}
        </button>
      </div>

      <div
        v-if="previewError"
        class="af-faint"
        style="margin: 0 0 6px; font-size: 11px; line-height: 1.7; color: var(--af-danger)"
      >
        这条 cron 存不下来：{{ previewError }}
      </div>
      <div
        v-else-if="preview && preview.times.length"
        class="af-faint"
        style="margin: 0 0 6px; font-size: 11px; line-height: 1.7"
      >
        接下来：<span class="mono">{{ preview.times.join(' · ') }}</span>
        <span>（{{ preview.timezone }}，{{ preview.cron }}）</span>
      </div>

      <RunVariablesForm
        v-model="scheduleValues"
        :variables="dslVariables"
      />

      <div style="display: flex; align-items: center; gap: 12px; margin-top: 12px; flex-wrap: wrap">
        <button
          v-if="!schedule"
          class="af-btn af-btn--sm af-btn--primary"
          :disabled="busy"
          @click="createSchedule"
        >
          创建定时触发
        </button>
        <template v-else>
          <button
            class="af-btn af-btn--sm af-btn--primary"
            :disabled="busy"
            @click="saveSchedule"
          >
            保存
          </button>
          <label
            class="af-faint"
            style="display: flex; gap: 4px; align-items: center; font-size: 12px"
          >
            <input
              type="checkbox"
              :checked="schedule.enabled"
              :disabled="busy"
              @change="toggle(schedule, ($event.target as HTMLInputElement).checked)"
            >
            启用
          </label>
          <span
            class="mono af-faint"
            style="font-size: 11px"
          >
            上次触发 {{ instantText(schedule.lastFiredAt) }}<template v-if="schedule.lastSlotAt">
              （{{ schedule.lastSlotAt }} 档 · {{ schedule.timezone }}）</template>
          </span>
          <span
            v-if="schedule.nextFireAt"
            class="mono af-faint"
            style="font-size: 11px"
          >
            下次 {{ schedule.nextFireAt }}（{{ schedule.timezone }}）
          </span>
          <div class="af-spacer" />
          <button
            class="af-btn af-btn--sm af-btn--danger"
            :disabled="busy"
            @click="remove(schedule)"
          >
            删除
          </button>
        </template>
      </div>
    </div>
  </div>
</template>
