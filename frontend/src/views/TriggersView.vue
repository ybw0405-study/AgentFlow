<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { triggerApi, workflowApi } from '@/api'
import { describeError, type DescribedError } from '@/api/errors'
import { formatInstant } from '@/utils/instant'
import { initialVariables } from '@/dsl/run-defaults'
import { MISFIRE_POLICY_VALUES, type MisfirePolicyValue } from '@/dsl/contract-enums'
import RunVariablesForm from '@/components/canvas/RunVariablesForm.vue'
import type { VariableSpec, WorkflowDsl } from '@/dsl/types'
import type {
  CronPreviewDto,
  MisfirePolicy,
  TriggerDto,
  WorkflowDefinitionDto,
} from '@/types/api'

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
const misfirePolicy = ref<MisfirePolicy>('CATCH_UP_LATEST')
/** 工作流级并发上限：0 = 不限。补跑一批档位时会逐个检查余量。 */
const concurrency = ref(0)
const concurrencyDraft = ref(0)
const concurrencyDirty = computed(() => concurrencyDraft.value !== concurrency.value)
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
  { label: '每 5 秒', value: '*/5 * * * * *' },
]

/**
 * 三种策略不是「补跑开关的三档力度」，而是三件不同的事：探活要跳过、对账要补齐、
 * 提醒不要迟到。所以每个选项都写清代价，而不是用「激进 / 保守」这种词。
 */
const MISFIRE_POLICY_TEXT: Record<MisfirePolicyValue, { label: string; hint: string }> = {
  CATCH_UP_LATEST: {
    label: '只补最近一档',
    hint: '停机再久也只跑一次——每分钟的任务不会瞬间补出上百条',
  },
  CATCH_UP_ALL: {
    label: '补齐错过的档位',
    hint: '按时间顺序回放最近 20 档，更老的丢弃并记审计——按小时对账不会缺一段',
  },
  SKIP: {
    label: '错过就跳过',
    hint: '只推游标、留一条审计，不补跑——下午补发早晨的提醒是噪音',
  },
}
// CT-1：取值来自单一 contract-enums.ts，这里只补展示文案，不再抄一遍枚举值。
const MISFIRE_POLICIES: { value: MisfirePolicy; label: string; hint: string }[] =
  MISFIRE_POLICY_VALUES.map((value) => ({ value, ...MISFIRE_POLICY_TEXT[value] }))

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
  concurrency.value = detail.definition.maxConcurrent ?? 0
  concurrencyDraft.value = concurrency.value
  misfirePolicy.value = schedule.value?.misfirePolicy ?? 'CATCH_UP_LATEST'
  scheduleValues.value = schedule.value
    ? { ...initialVariables(dslVariables.value), ...schedule.value.variables }
    : initialVariables(dslVariables.value)
  await loadPreview()
}

/** 「上次触发」是真实瞬间，按浏览器时区渲染；档位与下次是 cron 墙钟，原样展示。 */
function instantText(iso: string | null): string {
  return iso ? formatInstant(iso) : '从未'
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

async function run<T>(action: () => Promise<T>, message: string): Promise<T | null> {
  busy.value = true
  error.value = null
  notice.value = null
  try {
    const result = await action()
    notice.value = message
    await load()
    return result
  } catch (e) {
    error.value = describeError(e)
    return null
  } finally {
    busy.value = false
  }
}

/**
 * 一次性明文密钥只存在于 create/rotate 的响应里（SEC-3）。list 不再回传，
 * 因此用单独的 ref 承接；切走/刷新后清空，避免明文滞留在页面状态里。
 */
const revealedSecret = ref<string | null>(null)
const secretText = computed(() =>
  revealedSecret.value ?? (webhook.value?.hasSecret ? '' : null),
)
const secretHint = computed(() =>
  revealedSecret.value !== null
    ? '请现在复制保存；离开或刷新页面后不再显示。'
    : webhook.value?.hasSecret
      ? '密钥已设置。出于安全不回显，点击「轮换」可重新获取一次。'
      : '尚未设置密钥。',
)

function createWebhook(): void {
  void run(
    () => triggerApi.create(workflowId, { type: 'WEBHOOK' }),
    'Webhook 已创建，请复制签名密钥并复制链接',
  ).then((created) => {
    if (created?.secret) revealedSecret.value = created.secret
  })
}

function createSchedule(): void {
  void run(
    () => triggerApi.create(workflowId, {
      type: 'SCHEDULE',
      cron: cronDraft.value,
      misfirePolicy: misfirePolicy.value,
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
      misfirePolicy: misfirePolicy.value,
      variables: scheduleValues.value,
    }),
    '定时触发已更新',
  )
}

/**
 * 并发上限是工作流的属性，不是定时触发器的属性：同一个工作流可能既有 webhook 又有定时，
 * 两边各配一份只会互相打架。所以这里改的是 workflow 本身。
 */
function saveConcurrency(): void {
  void run(
    () => workflowApi.patch(workflowId, { maxConcurrent: concurrencyDraft.value }),
    concurrencyDraft.value > 0
      ? `并发上限设为 ${concurrencyDraft.value}`
      : '并发不再限制',
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
  void run(() => triggerApi.rotate(current.id), '已轮换令牌，请更新调用方').then((rotated) => {
    if (rotated?.secret) revealedSecret.value = rotated.secret
  })
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
            :value="secretText ?? ''"
            readonly
          >
          <button
            class="af-btn af-btn--sm"
            :disabled="secretText === null"
            @click="showSecret = !showSecret"
          >
            {{ showSecret ? '隐藏' : '显示' }}
          </button>
          <button
            class="af-btn af-btn--sm"
            :disabled="!secretText"
            @click="copy(secretText ?? '', '密钥')"
          >
            复制
          </button>
        </div>
        <p
          class="af-faint"
          style="margin: 0 0 10px; font-size: 11px; line-height: 1.7"
        >
          {{ secretHint }}
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
            创建于 {{ formatInstant(webhook.createdAt) }}
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
        >按 cron 自动跑（5 段到分钟，6 段支持秒级）；停机恢复后怎么补，看下面的策略（只看 24 小时内）</span>
      </div>

      <label class="af-label">cron（5 段：分 时 日 月 周；6 段：秒 分 时 日 月 周）</label>
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

      <div class="af-label">
        错过档位时
      </div>
      <div style="display: grid; gap: 5px; margin-bottom: 4px">
        <label
          v-for="item in MISFIRE_POLICIES"
          :key="item.value"
          class="af-faint"
          style="display: flex; gap: 6px; align-items: flex-start; font-size: 12px; line-height: 1.6"
        >
          <input
            v-model="misfirePolicy"
            type="radio"
            :value="item.value"
            :disabled="busy"
            style="margin-top: 3px"
          >
          <span>
            <span :class="misfirePolicy === item.value ? 'af-accent' : ''">{{ item.label }}</span>
            <span class="af-faint"> · {{ item.hint }}</span>
          </span>
        </label>
      </div>

      <div class="af-label">
        并发上限（整个工作流）
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 10px">
        <input
          v-model.number="concurrencyDraft"
          class="af-input mono"
          type="number"
          min="0"
          max="64"
          style="width: 84px"
          :disabled="busy"
        >
        <span
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; flex: 1"
        >
          0 = 不限。补跑一批档位时逐个检查余量，跑满就停——剩下的档位留到下一轮继续，
          不会丢档，也不会在同一秒把所有档位塞进引擎。
        </span>
        <button
          class="af-btn af-btn--sm"
          :disabled="busy || !concurrencyDirty"
          @click="saveConcurrency"
        >
          保存上限
        </button>
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
            <template v-if="schedule.lastOutcome === 'SKIP'">
              上次扫描跳过 {{ schedule.lastSlotAt ?? '—' }} 档（未执行）
            </template>
            <template v-else>
              上次触发 {{ instantText(schedule.lastFiredAt) }}<template v-if="schedule.lastSlotAt">
                （{{ schedule.lastSlotAt }} 档 · {{ schedule.timezone }}）</template>
            </template>
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
