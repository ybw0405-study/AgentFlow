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

function percent(value: number): string {
  return `${Math.round(value * 100)}%`
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

const usageRows = computed(() => summary.value?.byDefinition ?? [])
const recent = computed(() => summary.value?.recentPreviews ?? [])
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
        <div class="af-usage-bar">
          <span
            class="af-usage-bar__fill"
            :class="{ 'af-usage-bar__fill--danger': budgetState === 'over' }"
            :style="{ width: percent(budget?.callUtilization ?? 0) }"
          />
        </div>
        <div class="af-faint af-usage-card__foot">
          {{ periodLabel }} · 按节点计，含试跑
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
        <div class="af-usage-bar">
          <span
            class="af-usage-bar__fill"
            :class="{ 'af-usage-bar__fill--danger': budgetState === 'over' }"
            :style="{ width: percent(budget?.tokenUtilization ?? 0) }"
          />
        </div>
        <div
          class="af-faint af-usage-card__foot"
          :class="{ 'af-danger': budgetState !== 'ok' }"
        >
          {{ budgetState === 'over' ? '额度已用完，新的执行会被拒绝' : budgetState === 'near' ? '已用 80% 以上' : '额度充足' }}
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
        <span class="af-faint">峰值 {{ num(peak) }}</span>
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
              {{ row.latencyMs === null ? '—' : `${num(row.latencyMs)} ms` }}
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
