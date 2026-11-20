<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { VariableSpec } from '@/dsl/types'

defineOptions({ name: 'RunVariablesForm' })

const props = defineProps<{
  variables: Record<string, VariableSpec>
  modelValue: Record<string, unknown>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

const entries = computed<[string, VariableSpec][]>(() => Object.entries(props.variables))

/** 结构化变量（object/array）用文本框编辑 JSON：输入过程中不能每按一个键就回写。 */
const jsonText = ref<Record<string, string>>({})
const jsonError = ref<Record<string, string>>({})
const rawMode = ref(false)
const rawText = ref('')
const rawError = ref<string | null>(null)

watch(
  () => props.modelValue,
  (value) => {
    for (const [name, spec] of entries.value) {
      if (spec.type !== 'object' && spec.type !== 'array') continue
      if (jsonError.value[name]) continue // 正在改且还没改对，不要用外部值覆盖输入框
      jsonText.value[name] = JSON.stringify(value[name] ?? (spec.type === 'array' ? [] : {}), null, 2)
    }
    if (!rawMode.value) rawText.value = JSON.stringify(value, null, 2)
  },
  { immediate: true, deep: true },
)

function setValue(name: string, value: unknown): void {
  emit('update:modelValue', { ...props.modelValue, [name]: value })
}

function onBool(name: string, checked: boolean): void {
  setValue(name, checked)
}

function onNumber(name: string, spec: VariableSpec, raw: string): void {
  if (raw.trim() === '') {
    setValue(name, null)
    return
  }
  const parsed = spec.type === 'integer' ? Number.parseInt(raw, 10) : Number(raw)
  setValue(name, Number.isNaN(parsed) ? null : parsed)
}

function onJsonInput(name: string, spec: VariableSpec, raw: string): void {
  jsonText.value[name] = raw
  try {
    const parsed: unknown = JSON.parse(raw)
    const ok = spec.type === 'array' ? Array.isArray(parsed) : typeof parsed === 'object'
    if (!ok) {
      jsonError.value = { ...jsonError.value, [name]: `需要一个 ${spec.type}` }
      return
    }
    const next = { ...jsonError.value }
    delete next[name]
    jsonError.value = next
    setValue(name, parsed)
  } catch (e) {
    jsonError.value = {
      ...jsonError.value,
      [name]: `JSON 解析失败：${(e as Error).message}`,
    }
  }
}

function toggleRaw(): void {
  rawMode.value = !rawMode.value
  if (rawMode.value) {
    rawText.value = JSON.stringify(props.modelValue, null, 2)
    rawError.value = null
    return
  }
  commitRaw()
}

function commitRaw(): void {
  try {
    const parsed: unknown = JSON.parse(rawText.value)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      rawError.value = '入参必须是一个 JSON 对象'
      return
    }
    rawError.value = null
    emit('update:modelValue', parsed as Record<string, unknown>)
  } catch (e) {
    rawError.value = `JSON 解析失败：${(e as Error).message}`
  }
}

function placeholderOf(spec: VariableSpec): string {
  if (spec.default !== undefined && spec.default !== null) return String(spec.default)
  if (spec.description) return spec.description
  return spec.type === 'string' ? '文本' : spec.type
}
</script>

<template>
  <div class="af-runvars">
    <div class="af-runvars__head">
      <span
        class="af-label"
        style="margin: 0"
      >入参</span>
      <button
        class="af-btn af-btn--sm"
        type="button"
        :title="rawMode ? '回到按类型填写的表单' : '直接编辑完整 JSON'"
        @click="toggleRaw"
      >
        {{ rawMode ? '表单填写' : 'JSON 原文' }}
      </button>
    </div>

    <template v-if="rawMode">
      <textarea
        v-model="rawText"
        class="af-textarea mono"
        rows="8"
        spellcheck="false"
        @blur="commitRaw"
      />
      <p
        v-if="rawError"
        class="af-danger"
        style="font-size: 11px; margin: 4px 0 0"
      >
        {{ rawError }}
      </p>
    </template>

    <template v-else>
      <p
        v-if="!entries.length"
        class="af-faint"
        style="font-size: 12px; margin: 0"
      >
        这条工作流没有声明变量，直接运行即可。
      </p>
      <div
        v-for="[name, spec] in entries"
        :key="name"
        class="af-runvars__row"
      >
        <label
          class="af-runvars__name mono"
          :title="spec.description ?? name"
        >
          {{ name }}
          <span
            v-if="spec.required"
            class="af-accent"
          >*</span>
          <span class="af-runvars__type">{{ spec.type }}</span>
        </label>

        <input
          v-if="spec.type === 'boolean'"
          type="checkbox"
          :checked="modelValue[name] === true"
          @change="onBool(name, ($event.target as HTMLInputElement).checked)"
        >
        <input
          v-else-if="spec.type === 'number' || spec.type === 'integer'"
          class="af-input mono"
          type="number"
          :value="modelValue[name] ?? ''"
          :placeholder="placeholderOf(spec)"
          @input="onNumber(name, spec, ($event.target as HTMLInputElement).value)"
        >
        <input
          v-else-if="spec.type === 'string'"
          class="af-input"
          :value="modelValue[name] ?? ''"
          :placeholder="placeholderOf(spec)"
          @input="setValue(name, ($event.target as HTMLInputElement).value)"
        >
        <div
          v-else
          class="af-runvars__json"
        >
          <textarea
            class="af-textarea mono"
            rows="3"
            spellcheck="false"
            :value="jsonText[name] ?? ''"
            @input="onJsonInput(name, spec, ($event.target as HTMLTextAreaElement).value)"
          />
          <p
            v-if="jsonError[name]"
            class="af-danger"
            style="font-size: 11px; margin: 4px 0 0"
          >
            {{ jsonError[name] }}
          </p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.af-runvars__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.af-runvars__row {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 8px;
  align-items: start;
  margin-bottom: 6px;
}

.af-runvars__name {
  font-size: 12px;
  color: var(--af-text-dim);
  line-height: 26px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-runvars__type {
  margin-left: 6px;
  font-size: 10px;
  color: var(--af-text-faint);
}

.af-runvars__json {
  min-width: 0;
}
</style>
