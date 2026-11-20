<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '@/api'
import type {
  AdminAuditRow,
  AdminModelUsage,
  AdminTenantUsage,
  AdminTotals,
  AdminUsage,
} from '@/api'
import { describeError, type DescribedError } from '@/api/errors'

const tab = ref<'usage' | 'audit'>('usage')
const usage = ref<AdminUsage | null>(null)
const period = ref(currentMonth())
const auditItems = ref<AdminAuditRow[]>([])
const auditCursor = ref<string | null>(null)
const error = ref<DescribedError | null>(null)
const loading = ref(false)

const tenantRows = computed<AdminTenantUsage[]>(() => usage.value?.tenants ?? [])
const modelRows = computed<AdminModelUsage[]>(() => usage.value?.byModel ?? [])
const totals = computed<AdminTotals | null>(() => usage.value?.totals ?? null)
const modelTotal = computed(() => modelRows.value.reduce((sum, row) => sum + row.tokens, 0))

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

async function loadUsage(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    // 空字符串不要发出去：后端会当成本月，而输入框空着时用户以为看的是「全部」
    usage.value = await adminApi.usage(period.value === '' ? undefined : period.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadAudit(reset = true): Promise<void> {
  try {
    const page = (await adminApi.audit({
      cursor: reset ? undefined : (auditCursor.value ?? undefined),
    })) as { items: AdminAuditRow[]; nextCursor: string | null }
    auditItems.value = reset ? page.items : [...auditItems.value, ...page.items]
    auditCursor.value = page.nextCursor
  } catch (e) {
    error.value = describeError(e)
  }
}

function num(value: number | null | undefined): string {
  if (value == null) return '0'
  return value.toLocaleString('en-US')
}

/** 限额为空代表「没有上限」：后端对无上限不下发字段，不要渲染成 0。 */
function limitText(limit: number | null | undefined): string {
  return limit == null ? '无上限' : num(limit)
}

/**
 * 限额来自谁。继承时把祖先名字写出来——看到「5,000 · 继承自 Demo Org」才知道
 * 提高这个空间的额度是白费功夫，得去改 ORG 的账本。
 */
function limitSource(limitedBy: string | null | undefined, name: string | null | undefined,
                     selfId: string): string {
  if (limitedBy == null) return '无上限'
  if (limitedBy === selfId) return '本租户'
  return `继承自 ${name ?? limitedBy.slice(0, 8)}`
}

function pct(ratio: number | undefined): string {
  return `${Math.round((ratio ?? 0) * 100)}%`
}

/**
 * 占比文案：非零但不足 1% 时写「<1%」。
 * 台账里明明记了 115 token，卡片却写「占 0%」，管理员会以为没记上。
 */
function shareText(ratio: number | undefined): string {
  const value = ratio ?? 0
  if (value > 0 && value < 0.01) return '<1%'
  return pct(value)
}

function width(ratio: number | undefined): string {
  const clamped = Math.max(0, Math.min(1, ratio ?? 0))
  return `${(clamped * 100).toFixed(1)}%`
}

/**
 * 额度条切成「执行 / 试跑」两段。
 *
 * 试跑份额比已用量还大只可能是账本与明细不同步（记账失败、手工改库），按已用量封顶：
 * 宁可少画一段，也不要把墨迹画出进度条。没有上限时不画条，只显示数字。
 */
function bars(row: AdminTenantUsage) {
  const limit = row.effectiveTokenLimit
  if (limit == null || limit <= 0) {
    return { exec: '0%', preview: '0%', unbounded: true }
  }
  const used = row.tokensUsed ?? 0
  const preview = Math.min(row.previewTokens ?? 0, used)
  return {
    exec: width((used - preview) / limit),
    preview: width(preview / limit),
    unbounded: false,
  }
}

function stateLabel(state: string | undefined): string {
  if (state === 'over') return '已超限'
  if (state === 'near') return '接近上限'
  if (state === 'unbounded') return '无上限'
  return '正常'
}

function modelName(model: string | null | undefined): string {
  return model && model.length > 0 ? model : '未标注'
}

function modelShare(tokens: number): string {
  if (modelTotal.value <= 0) return '0%'
  return `${Math.round((tokens / modelTotal.value) * 100)}%`
}

onMounted(() => loadUsage())
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      管理端
    </h1>
    <div style="display: flex; gap: 8px; margin-bottom: 16px">
      <button
        class="af-btn af-btn--sm"
        :class="{ 'af-btn--primary': tab === 'usage' }"
        @click="tab = 'usage'; loadUsage()"
      >
        用量
      </button>
      <button
        class="af-btn af-btn--sm"
        :class="{ 'af-btn--primary': tab === 'audit' }"
        @click="tab = 'audit'; loadAudit()"
      >
        审计
      </button>
    </div>

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

    <div v-if="tab === 'usage'">
      <div
        class="af-admin-toolbar"
        style="display: flex; align-items: center; gap: 10px; margin-bottom: 12px"
      >
        <label
          class="af-faint"
          style="font-size: 11px"
        >账期</label>
        <input
          v-model="period"
          class="af-input"
          type="month"
          style="width: 150px"
          @change="loadUsage"
        >
        <button
          class="af-btn af-btn--sm"
          :disabled="loading"
          @click="loadUsage"
        >
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <span
          class="af-faint"
          style="font-size: 11px"
        >整页按账期统计，不含历史月份</span>
      </div>

      <div
        v-if="totals"
        class="af-admin-cards"
      >
        <div class="af-panel af-admin-card">
          <div class="af-faint af-admin-card__label">
            平台 token 合计
          </div>
          <div class="af-admin-card__value">
            {{ num(totals.tokens) }}
          </div>
          <div class="af-faint af-admin-card__foot">
            其中试跑 {{ num(totals.previewTokens) }}（占 {{ shareText(totals.previewShare) }}）
          </div>
        </div>
        <div class="af-panel af-admin-card">
          <div class="af-faint af-admin-card__label">
            本账期调用
          </div>
          <div class="af-admin-card__value">
            {{ num(totals.calls) }}
          </div>
          <div class="af-faint af-admin-card__foot">
            试跑 {{ num(totals.previewCalls) }} 次 · {{ totals.tenants }} 个租户
          </div>
        </div>
        <div class="af-panel af-admin-card">
          <div class="af-faint af-admin-card__label">
            执行
          </div>
          <div class="af-admin-card__value">
            {{ num(totals.executions) }}
          </div>
          <div
            class="af-faint af-admin-card__foot"
            :class="{ 'af-danger': totals.executionsFailed > 0 }"
          >
            失败 {{ num(totals.executionsFailed) }}
          </div>
        </div>
        <div class="af-panel af-admin-card">
          <div class="af-faint af-admin-card__label">
            额度告警
          </div>
          <div
            class="af-admin-card__value"
            :class="{ 'af-danger': totals.over > 0 }"
          >
            {{ totals.over + totals.near }}
          </div>
          <div class="af-faint af-admin-card__foot">
            已超限 {{ totals.over }} · 接近上限 {{ totals.near }}
          </div>
        </div>
      </div>

      <div
        class="af-panel"
        style="margin-bottom: 14px; margin-top: 14px"
      >
        <div class="af-admin-section">
          租户额度
          <span class="af-faint">有效限额取沿 parent 链的最小值，并标出来源</span>
        </div>
        <table class="af-table">
          <thead>
            <tr>
              <th>租户</th>
              <th
                style="text-align: right"
                title="按节点计的调用数（一次执行会调用多个节点），含试跑"
              >
                calls（节点）
              </th>
              <th style="text-align: right">
                tokens
              </th>
              <th style="text-align: right">
                试跑
              </th>
              <th style="text-align: right">
                执行
              </th>
              <th>有效限额（calls / tokens）</th>
              <th style="width: 22%">
                已用
              </th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in tenantRows"
              :key="row.tenantId"
            >
              <td>
                <span class="mono">{{ row.name }}</span>
                <span
                  class="af-faint"
                  style="font-size: 11px; margin-left: 6px"
                >{{ row.type }}</span>
              </td>
              <td
                class="mono"
                style="text-align: right"
              >
                {{ num(row.callsUsed) }}
              </td>
              <td
                class="mono"
                style="text-align: right"
              >
                {{ num(row.tokensUsed) }}
              </td>
              <td
                class="mono af-dim"
                style="text-align: right"
              >
                {{ num(row.previewTokens) }}
              </td>
              <td
                class="mono af-dim"
                style="text-align: right"
              >
                {{ num(row.executions) }}
                <span
                  v-if="row.executionsFailed"
                  class="af-danger"
                  style="font-size: 11px; margin-left: 4px"
                >
                  失败 {{ num(row.executionsFailed) }}
                </span>
              </td>
              <td>
                <div class="mono">
                  {{ limitText(row.effectiveCallLimit) }} / {{ limitText(row.effectiveTokenLimit) }}
                </div>
                <div
                  class="af-faint"
                  style="font-size: 11px"
                  :class="{ 'af-danger': row.state === 'near' }"
                >
                  {{ limitSource(row.limitedByToken, row.limitedByTokenName, row.tenantId) }}
                </div>
              </td>
              <td>
                <div
                  v-if="!bars(row).unbounded"
                  class="af-meter"
                  :title="`执行 ${num((row.tokensUsed ?? 0) - (row.previewTokens ?? 0))} · 试跑 ${num(row.previewTokens ?? 0)}`"
                >
                  <span
                    class="af-meter__fill"
                    :class="{ 'af-meter__fill--danger': row.state === 'over' }"
                    :style="{ width: bars(row).exec }"
                  />
                  <span
                    class="af-meter__fill af-meter__fill--preview"
                    :style="{ width: bars(row).preview }"
                  />
                </div>
                <div
                  class="af-faint mono"
                  style="font-size: 11px"
                >
                  {{ pct(row.tokenUtilization) }}
                </div>
              </td>
              <td>
                <span
                  class="af-status"
                  :class="{
                    'af-status--running': row.state === 'ok',
                    'af-status--failed': row.state === 'over' || row.state === 'near',
                  }"
                >{{ stateLabel(row.state) }}</span>
              </td>
            </tr>
            <tr v-if="!tenantRows.length">
              <td
                colspan="8"
                class="af-faint"
                style="text-align: center; padding: 20px"
              >
                这个账期还没有账本行
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="af-panel">
        <div class="af-admin-section">
          按模型
          <span class="af-faint">跨租户：执行看节点实际用过的模型，与试跑合并成一行</span>
        </div>
        <table class="af-table">
          <thead>
            <tr>
              <th>模型</th>
              <th style="text-align: right">
                执行 token
              </th>
              <th style="text-align: right">
                试跑 token
              </th>
              <th style="text-align: right">
                合计
              </th>
              <th style="text-align: right">
                租户数
              </th>
              <th style="width: 26%">
                占比
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in modelRows"
              :key="row.model ?? ''"
            >
              <td class="mono">
                {{ modelName(row.model) }}
                <span
                  v-if="!row.model"
                  class="af-faint"
                  style="font-size: 11px"
                >非 LLM 或输出未带模型</span>
              </td>
              <td
                class="mono"
                style="text-align: right"
              >
                {{ num(row.executionTokens) }}
              </td>
              <td
                class="mono"
                style="text-align: right"
              >
                {{ num(row.previewTokens) }}
              </td>
              <td
                class="mono"
                style="text-align: right"
              >
                {{ num(row.tokens) }}
              </td>
              <td
                class="mono af-dim"
                style="text-align: right"
              >
                {{ row.tenants }}
              </td>
              <td>
                <div class="af-meter">
                  <span
                    class="af-meter__fill"
                    :style="{ width: modelShare(row.tokens) }"
                  />
                </div>
                <span
                  class="af-faint"
                  style="font-size: 11px"
                >{{ modelShare(row.tokens) }}</span>
              </td>
            </tr>
            <tr v-if="!modelRows.length">
              <td
                colspan="6"
                class="af-faint"
                style="text-align: center; padding: 20px"
              >
                这个账期没有消耗
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div
      v-else
      class="af-panel"
    >
      <table class="af-table">
        <thead>
          <tr><th>seq</th><th>traceId</th><th>事件</th><th>租户</th><th>时间</th></tr>
        </thead>
        <tbody>
          <tr
            v-for="a in auditItems"
            :key="a.id"
          >
            <td class="mono af-faint">
              {{ a.seq }}
            </td>
            <td class="mono af-dim">
              {{ a.traceId.slice(0, 12) }}
            </td>
            <td class="mono">
              {{ a.eventType }}
            </td>
            <td class="mono af-dim">
              {{ a.tenantId.slice(0, 8) }}
            </td>
            <td class="mono af-dim">
              {{ a.createdAt?.slice(0, 19) }}
            </td>
          </tr>
          <tr v-if="!auditItems.length">
            <td
              colspan="5"
              class="af-faint"
              style="text-align: center; padding: 20px"
            >
              暂无
            </td>
          </tr>
        </tbody>
      </table>
      <div
        v-if="auditCursor"
        style="padding: 10px"
      >
        <button
          class="af-btn af-btn--sm"
          @click="loadAudit(false)"
        >
          加载更多
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.af-admin-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
}

.af-admin-card {
  padding: 12px 14px;
}

.af-admin-card__label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.af-admin-card__value {
  font-family: var(--af-mono);
  font-size: 20px;
  margin: 6px 0 4px;
}

.af-admin-card__foot {
  font-size: 11px;
}

.af-admin-section {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px 14px 8px;
  font-size: 12px;
  color: var(--af-text-dim);
}
</style>
