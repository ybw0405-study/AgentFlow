<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ConsoleMasthead from '@/components/design/ConsoleMasthead.vue'
import { adminApi } from '@/api'
import type {
  AdminAuditRow,
  AdminCross,
  AdminModelUsage,
  AdminSweepReport,
  AdminStaleExecution,
  AdminTenantUsage,
  AdminTotals,
  AdminUsage,
} from '@/api'
import { describeError, type DescribedError } from '@/api/errors'
import { formatInstant } from '@/utils/instant'

const tab = ref<'usage' | 'cross' | 'audit' | 'ops'>('usage')
const usage = ref<AdminUsage | null>(null)
const period = ref(currentMonth())
const auditItems = ref<AdminAuditRow[]>([])
const auditCursor = ref<string | null>(null)
const stale = ref<AdminStaleExecution[]>([])
const sweepReport = ref<AdminSweepReport | null>(null)
const sweeping = ref(false)
const error = ref<DescribedError | null>(null)
const loading = ref(false)
/** 审计「加载更多」防重入：同 cursor 并发会把同一页追加两次。 */
const auditLoading = ref(false)

const tenantRows = computed<AdminTenantUsage[]>(() => usage.value?.tenants ?? [])
const modelRows = computed<AdminModelUsage[]>(() => usage.value?.byModel ?? [])
const totals = computed<AdminTotals | null>(() => usage.value?.totals ?? null)
const modelTotal = computed(() => modelRows.value.reduce((sum, row) => sum + row.tokens, 0))
const cross = computed<AdminCross | null>(() => usage.value?.cross ?? null)
const crossColumns = computed(() => cross.value?.models ?? [])
const crossRows = computed(() => cross.value?.rows ?? [])

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
  if (auditLoading.value) return
  auditLoading.value = true
  try {
    const page = (await adminApi.audit({
      cursor: reset ? undefined : (auditCursor.value ?? undefined),
    })) as { items: AdminAuditRow[]; nextCursor: string | null }
    auditItems.value = reset ? page.items : [...auditItems.value, ...page.items]
    auditCursor.value = page.nextCursor
  } catch (e) {
    error.value = describeError(e)
  } finally {
    auditLoading.value = false
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

/** 交叉表的列名：空模型就是「未标注」，与用量页叫法一致。 */
function crossModelLabel(model: string | null | undefined): string {
  return model && model.length > 0 ? model : '未标注'
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
    return { exec: '0%', preview: '0%', tick: false, unbounded: true }
  }
  const used = row.tokensUsed ?? 0
  const preview = Math.min(row.previewTokens ?? 0, used)
  const previewRatio = preview / limit
  return {
    exec: width((used - preview) / limit),
    preview: width(previewRatio),
    // 非零但不足 1%：按百分比画出来不足 1px，挂最小宽度让它可见（与租户用量页同一规则）
    tick: previewRatio > 0 && previewRatio < 0.01,
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

/** 拉取看护候选：与看护实际会处理的同一套口径，只是不落任何状态。 */
async function loadStale(): Promise<void> {
  try {
    stale.value = await adminApi.staleExecutions(20)
  } catch (e) {
    error.value = describeError(e)
  }
}

/**
 * 手动跑一轮看护。
 *
 * 定时任务 60 秒一次，排查时等一轮比点一下难受，所以给一个「现在就跑」；
 * 后端的看护是幂等的（按引擎状态补投影 / 落终态），重复点没有副作用。
 */
async function sweepNow(): Promise<void> {
  sweeping.value = true
  error.value = null
  try {
    sweepReport.value = await adminApi.sweepExecutions()
    await loadStale()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    sweeping.value = false
  }
}

function openOps(): void {
  tab.value = 'ops'
  void loadStale()
}

/** 卡了多久：给人看的时间单位，超过一天就只说「天」。 */
function durationText(seconds: number): string {
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时`
  return `${Math.floor(seconds / 86400)} 天`
}

/** 卡住的原因：派发丢了 vs 跑了但没人收口，处置完全不同（重新运行 / 查引擎）。 */
function staleReason(row: AdminStaleExecution): string {
  return row.neverStarted ? '派发丢失，引擎从未收到' : '超过 deadline，未收到终态事件'
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

onMounted(() => loadUsage())
</script>

<template>
  <div class="af-page">
    <ConsoleMasthead
      no="08"
      code="OPS CONSOLE"
      title="管理端"
      zh="运维台"
      icon="shield"
      aside="额度、审计与卡住的执行都收在同一张台面上；每个动作都会写审计。"
    />
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
        :class="{ 'af-btn--primary': tab === 'cross' }"
        @click="tab = 'cross'; loadUsage()"
      >
        交叉表
      </button>
      <button
        class="af-btn af-btn--sm"
        :class="{ 'af-btn--primary': tab === 'audit' }"
        @click="tab = 'audit'; loadAudit()"
      >
        审计
      </button>
      <button
        class="af-btn af-btn--sm"
        :class="{ 'af-btn--primary': tab === 'ops' }"
        @click="openOps()"
      >
        看护
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
                    :class="{ 'af-meter__fill--tick': bars(row).tick }"
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
      v-else-if="tab === 'cross'"
    >
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
        >行是租户、列是模型；表格里的数字是 token，括号里是节点调用数</span>
      </div>

      <div
        class="af-panel"
        style="padding: 0"
      >
        <div style="overflow-x: auto">
          <table class="af-table af-cross">
            <thead>
              <tr>
                <th class="af-cross__sticky">
                  租户
                </th>
                <th
                  v-for="column in crossColumns"
                  :key="column.model ?? '__unlabeled'"
                  style="text-align: right; white-space: nowrap"
                >
                  {{ crossModelLabel(column.model) }}
                  <div
                    v-if="column.model == null"
                    class="af-faint"
                    style="font-size: 10px; font-weight: 400"
                  >
                    非 LLM 或输出未带模型
                  </div>
                </th>
                <th style="text-align: right">
                  合计
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in crossRows"
                :key="row.tenantId"
              >
                <td class="af-cross__sticky">
                  {{ row.name }}
                </td>
                <td
                  v-for="(cell, index) in row.cells"
                  :key="index"
                  class="mono"
                  style="text-align: right; white-space: nowrap"
                >
                  <template v-if="cell.tokens > 0">
                    {{ num(cell.tokens) }}
                    <span
                      class="af-faint"
                      style="font-size: 11px"
                    >（{{ num(cell.calls) }}）</span>
                  </template>
                  <span
                    v-else
                    class="af-faint"
                  >—</span>
                </td>
                <td
                  class="mono"
                  style="text-align: right"
                >
                  {{ num(row.tokens) }}
                  <span
                    class="af-faint"
                    style="font-size: 11px"
                  >（{{ num(row.calls) }}）</span>
                </td>
              </tr>
              <tr v-if="!crossRows.length">
                <td
                  :colspan="crossColumns.length + 2"
                  class="af-faint"
                  style="text-align: center; padding: 20px"
                >
                  这个账期没有模型调用记录
                </td>
              </tr>
            </tbody>
            <tfoot v-if="crossRows.length">
              <tr>
                <td class="af-cross__sticky af-faint">
                  合计
                </td>
                <td
                  v-for="column in crossColumns"
                  :key="column.model ?? '__unlabeled'"
                  class="mono"
                  style="text-align: right"
                >
                  {{ num(column.tokens) }}
                  <span
                    class="af-faint"
                    style="font-size: 11px"
                  >（{{ num(column.calls) }}）</span>
                </td>
                <td
                  class="mono"
                  style="text-align: right"
                >
                  {{ num(cross?.grand.tokens ?? 0) }}
                  <span
                    class="af-faint"
                    style="font-size: 11px"
                  >（{{ num(cross?.grand.calls ?? 0) }}）</span>
                </td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.7; margin: 8px 2px 0"
      >
        执行与试跑合并统计，口径与「用量」页一致（试跑按节点数计）。
        「未标注」是输出里没有模型名的节点——不按工作流默认模型猜，猜了就等于把「不知道」写成「知道」。
      </p>
    </div>

    <div
      v-else-if="tab === 'audit'"
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
              {{ formatInstant(a.createdAt) }}
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

    <div v-else>
      <div
        class="af-panel"
        style="padding: 12px 14px; margin-bottom: 14px"
      >
        <div
          class="af-admin-section"
          style="padding: 0 0 8px"
        >
          执行看护
          <span class="af-faint">卡在「运行中」的执行：引擎没发出终态事件，或者派发那一步就断了</span>
        </div>
        <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap">
          <button
            class="af-btn af-btn--sm af-btn--primary"
            :disabled="sweeping"
            @click="sweepNow"
          >
            {{ sweeping ? '巡检中…' : '立即巡检' }}
          </button>
          <span
            class="af-faint"
            style="font-size: 11px"
          >后台每 60 秒自动巡检一次；巡检按引擎侧状态补投影或落终态，可重复执行</span>
        </div>
        <div
          v-if="sweepReport"
          class="af-faint"
          style="font-size: 11px; margin-top: 8px"
        >
          本轮：扫描 {{ sweepReport.scanned }} 条，自愈 {{ sweepReport.reconciled }}、
          收口 {{ sweepReport.reaped }}、仍在运行 {{ sweepReport.alive }}、
          异常 {{ sweepReport.failed }}
        </div>
      </div>

      <div class="af-panel">
        <div class="af-admin-section">
          候选 {{ stale.length }} 条
          <span class="af-faint">按「卡得最久」排序，最多列 20 条</span>
        </div>
        <table class="af-table">
          <thead>
            <tr>
              <th>执行</th>
              <th>租户</th>
              <th>工作流</th>
              <th>状态</th>
              <th>已卡</th>
              <th>原因</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in stale"
              :key="row.executionId"
            >
              <td class="mono">
                {{ shortId(row.executionId) }}
              </td>
              <td class="mono af-dim">
                {{ shortId(row.tenantId) }}
              </td>
              <td class="mono af-dim">
                {{ shortId(row.definitionId) }}
              </td>
              <td>
                <span
                  class="af-status"
                  :class="row.neverStarted ? 'af-status--failed' : 'af-status--running'"
                >{{ row.status }}</span>
              </td>
              <td class="mono">
                {{ durationText(row.overdueSeconds) }}
              </td>
              <td
                class="af-faint"
                style="font-size: 11px"
              >
                {{ staleReason(row) }}
              </td>
              <td class="mono af-dim">
                {{ formatInstant(row.createdAt) }}
              </td>
            </tr>
            <tr v-if="!stale.length">
              <td
                colspan="7"
                class="af-faint"
                style="text-align: center; padding: 20px"
              >
                没有卡住的执行
              </td>
            </tr>
          </tbody>
        </table>
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

/*
 * 交叉表：列数随模型数量增长，横向滚动时租户列要钉在左边——
 * 否则滚到第五个模型就不知道这一行是谁了。
 */
.af-cross__sticky {
  position: sticky;
  left: 0;
  z-index: 1;
  background: var(--af-bg);
  white-space: nowrap;
}

.af-cross tfoot td {
  border-top: 1px solid var(--af-border);
  font-weight: 600;
}
</style>
