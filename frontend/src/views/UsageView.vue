<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usageApi } from '@/api'
import { errorText } from '@/api/errors'
import type { UsageSummary } from '@/types/api'

const WINDOWS = [7, 30, 90] as const

const days = ref(30)
const summary = ref<UsageSummary | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)

async function load(window = days.value): Promise<void> {
  days.value = window
  loading.value = true
  error.value = null
  try {
    summary.value = await usageApi.summary(window)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const budget = computed(() => summary.value?.budget ?? null)
const daily = computed(() => summary.value?.daily ?? [])

/** 柱子高度按区间内最高的一天归一；全 0 时用一个非零分母，避免除零。 */
const peak = computed(() => Math.max(1, ...daily.value.map((d) => d.tokens)))

function barHeight(tokens: number): string {
  if (tokens <= 0) return '0'
  return `${Math.max(3, Math.round((tokens / peak.value) * 100))}%`
}

function num(value: number): string {
  return value.toLocaleString('en-US')
}

/**
 * 百分比：保留一位小数。
 *
 * <p>取整会把 0.4% 抹成 0%，进度条上那一段就直接消失——「明明记了 115 token，条上什么都没有」。
 * 一位小数已经够读，也不会让 25% 变成 25.0%（整数时仍然只写整数）。
 */
function percent(value: number): string {
  return `${Math.round(value * 1000) / 10}%`
}

function shortDate(date: string): string {
  return date.slice(5)
}

/** 区间跨过两个月时，本月卡片要说明它只统计本月，否则数字看着对不上。 */
const periodLabel = computed(() => budget.value?.period ?? '')

const budgetState = computed<'ok' | 'near' | 'over'>(() => {
  const b = budget.value
  if (!b) return 'ok'
  const used = Math.max(b.tokenUtilization, b.callUtilization)
  if (used >= 1) return 'over'
  if (used >= 0.8) return 'near'
  return 'ok'
})

/** 两张卡片的额度条：调用数与 token 各切一次，模板里不再重复算。 */
const callSegments = computed(() =>
  budgetSegments(budget.value?.callUtilization ?? 0, budget.value?.previewCallUtilization),
)
const tokenSegments = computed(() =>
  budgetSegments(budget.value?.tokenUtilization ?? 0, budget.value?.previewTokenUtilization),
)

const usageRows = computed(() => summary.value?.byDefinition ?? [])
const recent = computed(() => summary.value?.recentPreviews ?? [])
const modelRows = computed(() => summary.value?.byModel ?? [])

/** 按模型分解的合计：占比用它当分母，各行加起来正好 100%。 */
const modelTotal = computed(() => modelRows.value.reduce((sum, row) => sum + row.tokens, 0))

function modelName(model: string | null | undefined): string {
  return model && model.length > 0 ? model : '未标注'
}

function modelShare(tokens: number): string {
  if (modelTotal.value <= 0) return '0%'
  return `${Math.round((tokens / modelTotal.value) * 100)}%`
}

/**
 * 已用量切成「执行 / 试跑」两段。
 *
 * <p>试跑的份额比已用量还大只可能是账本与明细不同步（记账失败、手工改库），
 * 这时按已用量封顶，宁可少画一段也不要把墨迹画出进度条。
 * 后端对 0 值不下发键，所以这里全部按 0 兜底。
 *
 * <p>{@code tick}：份额非零但不足 1%。百分比再精确，0.2% 在 300px 的条上也只有 0.6px，
 * 浏览器会画成空白——所以这一段额外挂一个 2px 的最小宽度，让它至少是一条看得见的刻度。
 * 份额真的是 0 时不挂：那时画一条刻度等于谎报「有一点试跑」。
 */
function budgetSegments(utilization: number, previewUtilization: number | undefined) {
  const preview = Math.min(previewUtilization ?? 0, utilization)
  return {
    exec: percent(utilization - preview),
    preview: percent(preview),
    tick: preview > 0 && preview < 0.01,
  }
}

/**
 * 试跑在「已用」里的占比：卡片脚注用它解释额度被谁吃掉了。
 * 不到 1% 时写成「<1%」而不是「0%」——明明记了 115 token，显示 0% 会让用户以为没记上。
 */
function previewShareOfUsed(preview: number | undefined, used: number | undefined): string {
  if (!used || used <= 0) return '0%'
  const pct = ((preview ?? 0) / used) * 100
  if (pct > 0 && pct < 1) return '<1%'
  return `${Math.round(pct)}%`
}
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      用量
    </h1>
    <p
      class="af-faint"
      style="margin: -8px 0 14px; font-size: 12px"
    >
      试跑也是真实调用：模型的 token 由上游真扣，所以这里和执行分开统计、一起计入本月额度。
    </p>

    <div
      v-if="error"
      class="af-error-box"
      style="margin-bottom: 12px"
    >
      {{ error }}
    </div>

    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 14px">
      <button
        v-for="w in WINDOWS"
        :key="w"
        class="af-btn af-btn--sm"
        :class="{ 'af-btn--primary': days === w }"
        :disabled="loading"
        @click="load(w)"
      >
        近 {{ w }} 天
      </button>
      <span
        v-if="summary"
        class="af-faint"
        style="font-size: 12px"
      >{{ summary.from }} ~ {{ summary.to }}</span>
    </div>

    <div class="af-usage-cards">
      <div class="af-panel af-usage-card">
        <div class="af-usage-card__label">
          本月调用
        </div>
        <div class="af-usage-card__value mono">
          {{ num(budget?.callsUsed ?? 0) }}
          <span class="af-usage-card__unit">/ {{ num(budget?.callLimit ?? 0) }}</span>
        </div>
        <div
          class="af-usage-bar"
          :title="`执行 ${num((budget?.callsUsed ?? 0) - (budget?.previewCalls ?? 0))} · 试跑 ${num(budget?.previewCalls ?? 0)}`"
        >
          <span
            class="af-usage-bar__fill"
            :class="{ 'af-usage-bar__fill--danger': budgetState === 'over' }"
            :style="{ width: callSegments.exec }"
          />
          <span
            class="af-usage-bar__fill af-usage-bar__fill--preview"
            :class="{ 'af-usage-bar__fill--tick': callSegments.tick }"
            :style="{ width: callSegments.preview }"
          />
        </div>
        <div class="af-faint af-usage-card__foot">
          {{ periodLabel }} · 按节点计，其中试跑 {{ num(budget?.previewCalls ?? 0) }} 次
        </div>
      </div>

      <div class="af-panel af-usage-card">
        <div class="af-usage-card__label">
          本月 token
        </div>
        <div class="af-usage-card__value mono">
          {{ num(budget?.tokensUsed ?? 0) }}
          <span class="af-usage-card__unit">/ {{ num(budget?.tokenLimit ?? 0) }}</span>
        </div>
        <div
          class="af-usage-bar"
          :title="`执行 ${num((budget?.tokensUsed ?? 0) - (budget?.previewTokens ?? 0))} · 试跑 ${num(budget?.previewTokens ?? 0)}`"
        >
          <span
            class="af-usage-bar__fill"
            :class="{ 'af-usage-bar__fill--danger': budgetState === 'over' }"
            :style="{ width: tokenSegments.exec }"
          />
          <span
            class="af-usage-bar__fill af-usage-bar__fill--preview"
            :class="{ 'af-usage-bar__fill--tick': tokenSegments.tick }"
            :style="{ width: tokenSegments.preview }"
          />
        </div>
        <div class="af-faint af-usage-card__foot">
          <span :class="{ 'af-danger': budgetState !== 'ok' }">
            {{ budgetState === 'over' ? '额度已用完，新的执行会被拒绝' : budgetState === 'near' ? '已用 80% 以上' : '额度充足' }}
          </span>
          <span> · 其中试跑 {{ num(budget?.previewTokens ?? 0) }}（占已用 {{ previewShareOfUsed(budget?.previewTokens, budget?.tokensUsed) }}）</span>
        </div>
      </div>

      <div class="af-panel af-usage-card">
        <div class="af-usage-card__label">
          区间执行消耗
        </div>
        <div class="af-usage-card__value mono">
          {{ num(summary?.executions.tokens ?? 0) }}
        </div>
        <div class="af-faint af-usage-card__foot">
          {{ num(summary?.executions.count ?? 0) }} 次执行
        </div>
      </div>

      <div class="af-panel af-usage-card">
        <div class="af-usage-card__label">
          区间试跑消耗
        </div>
        <div class="af-usage-card__value mono">
          {{ num(summary?.previews.tokens ?? 0) }}
        </div>
        <div class="af-faint af-usage-card__foot">
          {{ num(summary?.previews.count ?? 0) }} 次试跑 · 占 {{ percent(summary?.previewShare ?? 0) }}
        </div>
      </div>
    </div>

    <div
      class="af-panel"
      style="padding: 14px; margin-bottom: 14px"
    >
      <div class="af-usage-legend">
        <strong style="font-size: 12.5px; font-weight: 600">每日 token</strong>
        <span class="af-usage-dot af-usage-dot--exec" />执行
        <span class="af-usage-dot af-usage-dot--preview" />试跑
        <span class="af-spacer" />
        <span class="af-faint">峰值 {{ num(peak) }} · 额度条同色分段</span>
      </div>
      <div
        class="af-usage-chart"
        :class="{ 'af-usage-chart--dense': days > 45 }"
      >
        <div
          v-for="d in daily"
          :key="d.date"
          class="af-usage-chart__column"
          :title="`${d.date} · 执行 ${num(d.executionTokens)} · 试跑 ${num(d.previewTokens)}`"
        >
          <div class="af-usage-chart__stack">
            <span
              class="af-usage-chart__bar af-usage-chart__bar--preview"
              :style="{ height: barHeight(d.previewTokens) }"
            />
            <span
              class="af-usage-chart__bar af-usage-chart__bar--exec"
              :style="{ height: barHeight(d.executionTokens) }"
            />
          </div>
          <span
            v-if="days <= 30"
            class="af-usage-chart__tick"
          >{{ shortDate(d.date) }}</span>
        </div>
        <div
          v-if="!daily.length"
          class="af-faint"
          style="padding: 24px 0; text-align: center; width: 100%"
        >
          这个区间还没有消耗
        </div>
      </div>
    </div>

    <div
      class="af-panel"
      style="margin-bottom: 14px"
    >
      <div class="af-usage-section">
        按工作流
      </div>
      <table class="af-table">
        <thead>
          <tr>
            <th>工作流</th>
            <th style="text-align: right">
              执行
            </th>
            <th style="text-align: right">
              执行 token
            </th>
            <th style="text-align: right">
              试跑
            </th>
            <th style="text-align: right">
              试跑 token
            </th>
            <th style="text-align: right">
              合计
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in usageRows"
            :key="row.definitionId"
          >
            <td>{{ row.name }}</td>
            <td
              class="mono"
              style="text-align: right"
            >
              {{ num(row.executions) }}
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
              {{ num(row.previews) }}
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
          </tr>
          <tr v-if="!usageRows.length">
            <td
              colspan="6"
              class="af-faint"
              style="text-align: center; padding: 20px"
            >
              区间内没有消耗
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      class="af-panel"
      style="margin-bottom: 14px"
    >
      <div class="af-usage-section">
        按模型
        <span class="af-faint">执行看节点实际用过的模型，与试跑合并成一行</span>
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
            <th style="width: 30%">
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
            <td>
              <div class="af-usage-bar">
                <span
                  class="af-usage-bar__fill"
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
              colspan="5"
              class="af-faint"
              style="text-align: center; padding: 20px"
            >
              区间内没有消耗
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="af-panel">
      <div class="af-usage-section">
        最近试跑
        <span class="af-faint">账面对不上时，先看这里</span>
      </div>
      <table class="af-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>工作流</th>
            <th>节点</th>
            <th>模型</th>
            <th style="text-align: right">
              token
            </th>
            <th style="text-align: right">
              耗时
            </th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in recent"
            :key="row.id"
          >
            <td class="mono af-dim">
              {{ row.createdAt }}
            </td>
            <td>{{ row.definitionName ?? row.definitionId.slice(0, 8) }}</td>
            <td class="mono">
              {{ row.nodeKey }}
            </td>
            <td class="mono af-dim">
              {{ row.model ?? '—' }}
            </td>
            <td
              class="mono"
              style="text-align: right"
            >
              {{ num(row.tokenUsed) }}
            </td>
            <td
              class="mono af-dim"
              style="text-align: right"
            >
              {{ row.latencyMs == null ? '—' : `${num(row.latencyMs)} ms` }}
            </td>
            <td
              class="mono"
              :class="row.status === 'SUCCEEDED' ? 'af-faint' : 'af-danger'"
            >
              {{ row.status }}
            </td>
          </tr>
          <tr v-if="!recent.length">
            <td
              colspan="7"
              class="af-faint"
              style="text-align: center; padding: 20px"
            >
              还没有试跑记录
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.af-usage-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.af-usage-card {
  padding: 12px 14px;
}

.af-usage-card__label {
  font-size: 11px;
  color: var(--af-text-faint);
  margin-bottom: 6px;
}

.af-usage-card__value {
  font-size: 20px;
  line-height: 1.2;
}

.af-usage-card__unit {
  font-size: 11px;
  color: var(--af-text-faint);
}

.af-usage-card__foot {
  margin-top: 8px;
  font-size: 11px;
}

.af-usage-bar {
  height: 3px;
  background: var(--af-surface);
  border-radius: 2px;
  overflow: hidden;
  margin-top: 8px;
  /* 两段（执行 / 试跑）并排铺满已用部分，宽度加起来正好是利用率 */
  display: flex;
}

.af-usage-bar__fill {
  display: block;
  height: 100%;
  background: var(--af-accent);
  transition: width var(--af-motion);
}

.af-usage-bar__fill--danger {
  background: var(--af-danger);
}

/* 与每日柱状图里「试跑」同色：同一页面上同一种消耗只能有一种颜色 */
.af-usage-bar__fill--preview {
  background: var(--af-neutral);
}

/*
 * 不足 1% 的试跑段：按百分比算出来的宽度不到 1px，浏览器直接画不出来。
 * 给一个 2px 的最小宽度，让它退化成一条刻度——「有一点，但很少」，而不是「没有」。
 */
.af-usage-bar__fill--tick {
  min-width: 2px;
}

.af-usage-legend {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--af-text-dim);
  margin-bottom: 12px;
}

.af-usage-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
  margin-left: 6px;
}

.af-usage-dot--exec {
  background: var(--af-accent);
}

.af-usage-dot--preview {
  background: var(--af-neutral);
}

.af-usage-chart {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 132px;
  overflow: hidden;
}

.af-usage-chart--dense {
  gap: 1px;
}

.af-usage-chart__column {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
}

.af-usage-chart__stack {
  width: 100%;
  max-width: 22px;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 1px;
}

.af-usage-chart__bar {
  width: 100%;
  min-height: 0;
  border-radius: 1px;
  display: block;
}

.af-usage-chart__bar--exec {
  background: var(--af-accent);
  opacity: 0.85;
}

.af-usage-chart__bar--preview {
  background: var(--af-neutral);
}

.af-usage-chart__tick {
  font-size: 9px;
  color: var(--af-text-faint);
  margin-top: 4px;
  white-space: nowrap;
}

.af-usage-section {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--af-border);
  font-size: 12px;
  font-weight: 600;
}
</style>
