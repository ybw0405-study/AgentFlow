<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NODE_TYPE_METAS } from '@/dsl/types'
import type { NodeType } from '@/dsl/types'
import { NODE_TYPE_LABELS } from '@/dsl/node-card'

defineOptions({ name: 'NodeSearch' })

const props = defineProps<{
  inSubgraph: boolean
  hasStart: boolean
  anchorId?: string | null
}>()

const emit = defineEmits<{
  add: [type: NodeType, connect: boolean]
  close: []
}>()

const query = ref('')
const active = ref(0)
const input = ref<HTMLInputElement | null>(null)

const items = computed(() => {
  const q = query.value.trim().toLowerCase()
  return NODE_TYPE_METAS.filter((m) => {
    if (m.type === 'start' && (props.inSubgraph || props.hasStart)) return false
    if (!q) return true
    const haystack = `${m.type} ${m.label} ${m.description} ${NODE_TYPE_LABELS[m.type] ?? ''}`
    return haystack.toLowerCase().includes(q)
  })
})

function choose(type: NodeType): void {
  emit('add', type, Boolean(props.anchorId))
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = Math.min(active.value + 1, Math.max(items.value.length - 1, 0))
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(active.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const item = items.value[active.value]
    if (item) choose(item.type)
  }
}

onMounted(() => input.value?.focus())
</script>

<template>
  <div
    class="af-search-backdrop"
    @click.self="emit('close')"
  >
    <div class="af-search">
      <input
        ref="input"
        v-model="query"
        class="af-search__input mono"
        placeholder="搜索节点：审批 / 并行 / 循环 / llm…"
        spellcheck="false"
        @keydown="onKeydown"
        @input="active = 0"
      >
      <div class="af-search__list">
        <button
          v-for="(m, i) in items"
          :key="m.type"
          class="af-search__item"
          :class="{ 'af-search__item--active': i === active }"
          @mouseenter="active = i"
          @click="choose(m.type)"
        >
          <span class="af-search__name">{{ NODE_TYPE_LABELS[m.type] }}</span>
          <span class="af-search__type mono">{{ m.type }}</span>
          <span class="af-search__desc">{{ m.description }}</span>
        </button>
        <div
          v-if="!items.length"
          class="af-search__empty"
        >
          没有匹配的节点类型
        </div>
      </div>
      <div class="af-search__foot">
        <span>↑↓ 选择 · Enter 落位 · Esc 关闭</span>
        <span v-if="anchorId">将自动接到选中节点</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.af-search-backdrop {
  position: absolute;
  inset: 0;
  background: var(--af-backdrop);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 72px;
  z-index: 20;
}

.af-search {
  width: 440px;
  max-width: calc(100% - 32px);
  background: var(--af-surface);
  border: 1px solid var(--af-border-strong);
  border-radius: var(--af-radius);
  overflow: hidden;
}

.af-search__input {
  width: 100%;
  padding: 10px 12px;
  background: var(--af-panel);
  border: none;
  border-bottom: 1px solid var(--af-border);
  color: var(--af-text);
  font-size: 13px;
  outline: none;
}

.af-search__input:focus {
  border-bottom-color: var(--af-accent-border);
}

.af-search__list {
  max-height: 300px;
  overflow-y: auto;
}

.af-search__item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  padding: 7px 12px;
  background: transparent;
  border: none;
  border-left: 2px solid transparent;
  color: var(--af-text);
  text-align: left;
  cursor: pointer;
}

.af-search__item--active {
  background: var(--af-raised);
  border-left-color: var(--af-accent);
}

.af-search__name {
  font-size: 12px;
  min-width: 68px;
}

.af-search__type {
  font-size: 11px;
  color: var(--af-accent);
  min-width: 88px;
}

.af-search__desc {
  font-size: 11px;
  color: var(--af-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-search__empty {
  padding: 18px 12px;
  font-size: 12px;
  color: var(--af-text-faint);
  text-align: center;
}

.af-search__foot {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 12px;
  border-top: 1px solid var(--af-border);
  font-size: 11px;
  color: var(--af-text-faint);
}
</style>
