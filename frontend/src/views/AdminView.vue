<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api'
import type { AdminAuditRow, AdminBudgetRow, AdminUsageRow } from '@/api'
import { errorText } from '@/api/errors'

const tab = ref<'usage' | 'audit'>('usage')
const budgetLedger = ref<AdminBudgetRow[]>([])
const executions = ref<AdminUsageRow[]>([])
const auditItems = ref<AdminAuditRow[]>([])
const auditCursor = ref<string | null>(null)
const error = ref<string | null>(null)

async function loadUsage(): Promise<void> {
  try {
    const data = (await adminApi.usage()) as {
      budgetLedger: AdminBudgetRow[]
      executions: AdminUsageRow[]
    }
    budgetLedger.value = data.budgetLedger ?? []
    executions.value = data.executions ?? []
  } catch (e) {
    error.value = errorText(e)
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
    error.value = errorText(e)
  }
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
      {{ error }}
    </div>

    <div v-if="tab === 'usage'">
      <div
        class="af-panel"
        style="margin-bottom: 12px"
      >
        <table class="af-table">
          <thead>
            <tr><th>租户</th><th>周期</th><th>calls</th><th>tokens</th><th>限额</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="b in budgetLedger"
              :key="b.tenantId + b.period"
            >
              <td class="mono">
                {{ b.tenantId.slice(0, 8) }}
              </td>
              <td class="mono">
                {{ b.period }}
              </td>
              <td class="mono">
                {{ b.callsUsed }}
              </td>
              <td class="mono">
                {{ b.tokensUsed }}
              </td>
              <td class="mono af-dim">
                {{ b.limitCalls }} / {{ b.limitTokens }}
              </td>
            </tr>
            <tr v-if="!budgetLedger.length">
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
      </div>
      <div class="af-panel">
        <table class="af-table">
          <thead>
            <tr><th>租户</th><th>状态</th><th>执行数</th><th>tokens</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="(u, i) in executions"
              :key="i"
            >
              <td class="mono">
                {{ u.tenantId.slice(0, 8) }}
              </td>
              <td><span class="af-status">{{ u.status }}</span></td>
              <td class="mono">
                {{ u.cnt }}
              </td>
              <td class="mono">
                {{ u.tokens }}
              </td>
            </tr>
            <tr v-if="!executions.length">
              <td
                colspan="4"
                class="af-faint"
                style="text-align: center; padding: 20px"
              >
                暂无
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
