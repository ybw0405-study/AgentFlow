<script setup lang="ts">
import type { VariableSpec } from '@/dsl/types'

defineOptions({ name: 'VariablesPanel' })

const props = defineProps<{ variables: Record<string, VariableSpec> }>()

const emit = defineEmits<{
  update: [variables: Record<string, VariableSpec>]
}>()

const TYPES: VariableSpec['type'][] = ['string', 'number', 'integer', 'boolean', 'object', 'array']

function entries(): [string, VariableSpec][] {
  return Object.entries(props.variables)
}

function updateName(index: number, name: string): void {
  const list = entries()
  const [, spec] = list[index]
  list.splice(index, 1)
  const next = Object.fromEntries(list)
  if (name && !(name in next)) next[name] = spec
  emit('update', next)
}

function updateSpec(index: number, patch: Partial<VariableSpec>): void {
  const list = entries()
  list[index][1] = { ...list[index][1], ...patch }
  emit('update', Object.fromEntries(list))
}

function add(): void {
  let i = entries().length + 1
  while (`var${i}` in props.variables) i++
  emit('update', { ...props.variables, [`var${i}`]: { type: 'string', required: true } })
}

function remove(index: number): void {
  const list = entries()
  list.splice(index, 1)
  emit('update', Object.fromEntries(list))
}
</script>

<template>
  <div class="af-vars">
    <div
      class="af-label"
      style="margin-top: 0"
    >
      变量（variables）
    </div>
    <div
      v-for="([name, spec], i) in entries()"
      :key="i"
      class="af-vars__row"
    >
      <input
        class="af-input mono"
        :value="name"
        placeholder="名称"
        @change="updateName(i, ($event.target as HTMLInputElement).value)"
      >
      <select
        class="af-select"
        :value="spec.type"
        @change="updateSpec(i, { type: ($event.target as HTMLSelectElement).value as VariableSpec['type'] })"
      >
        <option
          v-for="t in TYPES"
          :key="t"
          :value="t"
        >
          {{ t }}
        </option>
      </select>
      <label
        class="af-faint"
        style="display: flex; gap: 3px; align-items: center; font-size: 11px"
      >
        <input
          type="checkbox"
          :checked="spec.required ?? false"
          @change="updateSpec(i, { required: ($event.target as HTMLInputElement).checked })"
        >
        必填
      </label>
      <label
        class="af-faint"
        style="display: flex; gap: 3px; align-items: center; font-size: 11px"
        title="标记为 PII 变量，强制双层脱敏"
      >
        <input
          type="checkbox"
          :checked="spec.pii ?? false"
          @change="updateSpec(i, { pii: ($event.target as HTMLInputElement).checked })"
        >
        PII
      </label>
      <button
        class="af-btn af-btn--sm"
        @click="remove(i)"
      >
        ×
      </button>
    </div>
    <button
      class="af-btn af-btn--sm"
      style="margin-top: 6px"
      @click="add"
    >
      + 变量
    </button>
  </div>
</template>

<style scoped>
.af-vars {
  padding: 12px;
  border-top: 1px solid var(--af-border);
  background: var(--af-panel);
}

.af-vars__row {
  display: grid;
  grid-template-columns: 1fr 88px auto auto auto;
  gap: 6px;
  align-items: center;
  margin-bottom: 4px;
}
</style>
