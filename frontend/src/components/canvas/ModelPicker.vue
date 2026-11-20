<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  MODEL_PRESET_GROUPS,
  searchModelPresets,
} from '@/dsl/model-presets'
import type { ModelPreset } from '@/dsl/model-presets'

defineOptions({ name: 'ModelPicker' })

defineProps<{
  currentModel: string
}>()

const emit = defineEmits<{
  select: [preset: ModelPreset]
  builtin: []
  custom: []
  manage: []
  close: []
}>()

type Row =
  | { kind: 'builtin'; index: number }
  | { kind: 'preset'; index: number; preset: ModelPreset }

const query = ref('')
const active = ref(0)
const input = ref<HTMLInputElement | null>(null)

const BUILTIN_KEYWORDS = ['内置', '演示', '离线', '可跑', 'mock', 'demo', 'builtin', '无需配置']

const builtinVisible = computed(() => {
  const q = query.value.trim().toLowerCase()
  return !q || BUILTIN_KEYWORDS.some((k) => k.toLowerCase().includes(q) || q.includes(k.toLowerCase()))
})

const matched = computed(() => searchModelPresets(query.value))

/** 分组只是轻量小标题，主体仍是平铺列表，选起来不用多想层级。 */
const sections = computed(() => {
  let index = 0
  const out: { label: string; rows: Row[] }[] = []
  if (builtinVisible.value) {
    out.push({ label: '快捷', rows: [{ kind: 'builtin', index: index++ }] })
  }
  for (const group of MODEL_PRESET_GROUPS) {
    const rows: Row[] = matched.value
      .filter((p) => p.group === group.id)
      .map((preset) => ({ kind: 'preset', index: index++, preset }))
    if (rows.length) out.push({ label: group.label, rows })
  }
  return out
})

const flat = computed<Row[]>(() => sections.value.flatMap((s) => s.rows))

function choose(row: Row): void {
  if (row.kind === 'builtin') emit('builtin')
  else emit('select', row.preset)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = Math.min(active.value + 1, Math.max(flat.value.length - 1, 0))
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(active.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const row = flat.value[active.value]
    if (row) choose(row)
  }
}

onMounted(() => input.value?.focus())
</script>

<template>
  <div
    class="af-picker-backdrop"
    @click.self="emit('close')"
  >
    <div class="af-picker">
      <div class="af-picker__head">
        <div>
          <div class="af-picker__title">
            选择模型
          </div>
          <div class="af-picker__sub">
            列的是市面常见服务；选完填一次 API Key，验证通过就能直接跑
          </div>
        </div>
        <button
          class="af-picker__close"
          aria-label="关闭"
          @click="emit('close')"
        >
          关闭
        </button>
      </div>

      <input
        ref="input"
        v-model="query"
        class="af-picker__search mono"
        placeholder="搜索模型 / 厂商：hy4、DeepSeek、GLM、Kimi…"
        spellcheck="false"
        @keydown="onKeydown"
        @input="active = 0"
      >

      <div class="af-picker__list">
        <template
          v-for="section in sections"
          :key="section.label"
        >
          <div class="af-picker__group">
            {{ section.label }}
          </div>
          <button
            v-for="row in section.rows"
            :key="row.kind === 'preset' ? row.preset.id : 'builtin'"
            class="af-picker__row"
            :class="{ 'af-picker__row--active': row.index === active }"
            @mouseenter="active = row.index"
            @click="choose(row)"
          >
            <span
              v-if="row.kind === 'preset'"
              class="af-picker__mark mono"
            >{{ row.preset.mark }}</span>
            <span
              v-else
              class="af-picker__mark af-picker__mark--builtin mono"
            >AF</span>
            <span class="af-picker__text">
              <span class="af-picker__name">
                {{ row.kind === 'preset' ? row.preset.label : '内置演示模型（离线可跑）' }}
              </span>
              <span class="af-picker__desc">
                {{ row.kind === 'preset'
                  ? `${row.preset.vendor} · ${row.preset.note}`
                  : '不联网、不需要 Key；当前模板默认就是它，点这里可切回来' }}
              </span>
            </span>
            <span
              v-if="row.kind === 'preset' && row.preset.tag"
              class="af-picker__tag"
              :class="{ 'af-picker__tag--warn': row.preset.needsServerOptIn }"
            >{{ row.preset.tag }}</span>
            <span
              v-else-if="row.kind === 'builtin'"
              class="af-picker__tag"
            >无需配置</span>
          </button>
        </template>
        <div
          v-if="!flat.length"
          class="af-picker__empty"
        >
          没有匹配的预设，可以用下面的「自定义端点」接任意 OpenAI 兼容服务
        </div>
      </div>

      <div class="af-picker__foot">
        <button
          class="af-btn af-btn--primary af-btn--sm"
          @click="emit('custom')"
        >
          自定义 OpenAI 兼容端点
        </button>
        <button
          class="af-btn af-btn--sm"
          @click="emit('manage')"
        >
          管理已有连接
        </button>
        <span class="af-spacer" />
        <span class="af-picker__hint">↑↓ 选择 · Enter 确认 · Esc 关闭</span>
      </div>
      <div
        v-if="currentModel"
        class="af-picker__current mono"
      >
        当前节点：{{ currentModel }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.af-picker-backdrop {
  position: fixed;
  inset: 0;
  background: var(--af-backdrop);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 64px;
  z-index: 30;
}

.af-picker {
  width: 620px;
  max-width: calc(100% - 32px);
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  background: var(--af-surface);
  border: 1px solid var(--af-border-strong);
  border-radius: var(--af-radius);
  overflow: hidden;
}

.af-picker__head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px 10px;
}

.af-picker__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--af-text);
}

.af-picker__sub {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--af-text-faint);
  line-height: 1.5;
}

.af-picker__close {
  flex: none;
  background: transparent;
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  color: var(--af-text-dim);
  font-size: 11px;
  padding: 3px 8px;
  cursor: pointer;
}

.af-picker__close:hover {
  color: var(--af-accent);
  border-color: var(--af-accent-border);
}

.af-picker__search {
  width: 100%;
  padding: 9px 16px;
  background: var(--af-panel);
  border: none;
  border-top: 1px solid var(--af-border);
  border-bottom: 1px solid var(--af-border);
  color: var(--af-text);
  font-size: 12.5px;
  outline: none;
}

.af-picker__search:focus {
  border-bottom-color: var(--af-accent-border);
}

.af-picker__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 0 8px;
}

.af-picker__group {
  padding: 8px 16px 4px;
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--af-text-faint);
}

.af-picker__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 16px;
  background: transparent;
  border: none;
  border-left: 2px solid transparent;
  color: var(--af-text);
  text-align: left;
  cursor: pointer;
}

.af-picker__row--active {
  background: var(--af-raised);
  border-left-color: var(--af-accent);
}

.af-picker__mark {
  flex: none;
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  background: var(--af-panel);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  color: var(--af-text-dim);
  font-size: 10px;
  letter-spacing: 0.02em;
}

.af-picker__row--active .af-picker__mark {
  background: var(--af-accent-dim);
  border-color: var(--af-accent-border);
  color: var(--af-accent);
}

.af-picker__mark--builtin {
  color: var(--af-neutral);
}

.af-picker__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.af-picker__name {
  font-size: 12.5px;
}

.af-picker__desc {
  font-size: 11px;
  color: var(--af-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-picker__tag {
  flex: none;
  padding: 2px 7px;
  border-radius: var(--af-radius-sm);
  background: var(--af-accent-dim);
  color: var(--af-accent);
  font-size: 10px;
  white-space: nowrap;
}

.af-picker__tag--warn {
  background: var(--af-panel);
  color: var(--af-text-dim);
}

.af-picker__empty {
  padding: 20px 16px;
  font-size: 12px;
  color: var(--af-text-faint);
  text-align: center;
}

.af-picker__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  border-top: 1px solid var(--af-border);
}

.af-picker__hint,
.af-picker__current {
  font-size: 10.5px;
  color: var(--af-text-faint);
}

.af-picker__current {
  padding: 0 16px 9px;
}
</style>
