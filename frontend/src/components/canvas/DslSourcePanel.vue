<script setup lang="ts">
import { ref, watch } from 'vue'
import type { WorkflowDsl } from '@/dsl/types'

defineOptions({ name: 'DslSourcePanel' })

const props = defineProps<{ dsl: WorkflowDsl }>()
const emit = defineEmits<{ apply: [dsl: WorkflowDsl]; close: [] }>()

const text = ref('')
const error = ref<string | null>(null)

watch(
  () => props.dsl,
  (dsl) => {
    text.value = JSON.stringify(dsl, null, 2)
  },
  { immediate: true, deep: false },
)

function apply(): void {
  error.value = null
  try {
    const parsed = JSON.parse(text.value) as WorkflowDsl
    if (parsed.dslVersion !== '1.0' || !Array.isArray(parsed.nodes)) {
      error.value = '缺少 dslVersion=1.0 或 nodes'
      return
    }
    emit('apply', parsed)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'JSON 解析失败'
  }
}
</script>

<template>
  <div class="af-source">
    <div style="display: flex; gap: 8px; padding: 8px 12px; align-items: center">
      <span
        class="af-label"
        style="margin: 0"
      >DSL 源码</span>
      <div class="af-spacer" />
      <span
        v-if="error"
        class="af-danger mono"
        style="font-size: 11px"
      >{{ error }}</span>
      <button
        class="af-btn af-btn--sm af-btn--primary"
        @click="apply"
      >
        应用
      </button>
      <button
        class="af-btn af-btn--sm"
        @click="emit('close')"
      >
        关闭
      </button>
    </div>
    <textarea
      v-model="text"
      class="af-source__editor"
      spellcheck="false"
    />
  </div>
</template>

<style scoped>
.af-source {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: var(--af-bg);
  z-index: 5;
}

.af-source textarea {
  flex: 1;
  margin: 0 12px 12px;
  background: var(--af-panel);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
  color: var(--af-text);
  font-family: var(--af-mono);
  font-size: 12px;
  line-height: 1.6;
  padding: 10px;
  outline: none;
  resize: none;
}
</style>
