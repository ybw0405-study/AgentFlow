<script setup lang="ts">
import type { ExecutionDto } from '@/types/api'
import { formatInstant } from '@/utils/instant'

defineOptions({ name: 'TracePanel' })

defineProps<{ execution: ExecutionDto }>()
</script>

<template>
  <div style="padding: 12px; overflow: auto">
    <div
      class="af-label"
      style="margin-top: 0"
    >
      追踪
    </div>
    <div class="af-trace__row">
      <span class="af-faint">traceId</span>
      <span
        class="mono af-dim"
        style="word-break: break-all"
      >{{ execution.traceId }}</span>
    </div>
    <div class="af-trace__row">
      <span class="af-faint">token</span>
      <span class="mono af-accent">{{ execution.tokenUsed }} / {{ execution.tokenBudget ?? '∞' }}</span>
    </div>
    <div class="af-trace__row">
      <span class="af-faint">steps</span>
      <span class="mono">{{ execution.stepsUsed }} / {{ execution.maxSteps ?? '∞' }}</span>
    </div>
    <div class="af-trace__row">
      <span class="af-faint">errorCode</span>
      <span class="mono af-danger">{{ execution.errorCode ?? '—' }}</span>
    </div>
    <div class="af-trace__row">
      <span class="af-faint">started</span>
      <span class="mono af-dim">{{ formatInstant(execution.startedAt) }}</span>
    </div>
    <div class="af-trace__row">
      <span class="af-faint">finished</span>
      <span class="mono af-dim">{{ formatInstant(execution.finishedAt) }}</span>
    </div>
  </div>
</template>

<style scoped>
.af-trace__row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 0;
  border-bottom: 1px solid var(--af-border);
  font-size: 12px;
}
</style>
