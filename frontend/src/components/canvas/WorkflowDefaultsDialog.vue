<script setup lang="ts">
import { computed, ref } from 'vue'
import type { LlmDefaults } from '@/dsl/types'
import type { ModelFactsDto } from '@/types/api'
import ModelFactsChips from './ModelFactsChips.vue'

defineOptions({ name: 'WorkflowDefaultsDialog' })

const props = defineProps<{
  current: LlmDefaults | undefined
  catalog: {
    configured: boolean
    defaultModel: string
    models: string[]
    error: string | null
    items?: ModelFactsDto[]
  }
  connections: { id: string; name: string; models: string[]; defaultModel: string }[]
  llmNodeCount: number
}>()

const emit = defineEmits<{
  save: [value: { llm: LlmDefaults; applyAll: boolean }]
  close: []
}>()

const source = ref<string>(props.current?.connectionId ?? '')
const model = ref<string>(props.current?.model ?? props.catalog.defaultModel)
const applyAll = ref(true)

const activeConnection = computed(
  () => props.connections.find((c) => c.id === source.value) ?? null,
)

/** 模型候选：进程端点用服务端清单，连接用该连接探测到的清单。 */
const modelOptions = computed<string[]>(() => {
  if (source.value) return activeConnection.value?.models ?? []
  return props.catalog.models
})

const fallback = computed(() => {
  if (source.value) return activeConnection.value?.defaultModel ?? ''
  return props.catalog.defaultModel
})

function pickModel(name: string): void {
  model.value = name
}

function save(): void {
  const next: LlmDefaults = {}
  if (source.value) next.connectionId = source.value
  const resolved = model.value.trim() || fallback.value
  if (resolved) next.model = resolved
  emit('save', { llm: next, applyAll: applyAll.value })
}
</script>

<template>
  <div
    class="af-modal-backdrop"
    @click.self="emit('close')"
  >
    <div
      class="af-panel af-modal"
      style="width: 520px; padding: 18px"
    >
      <div
        class="af-page-title"
        style="margin-bottom: 6px"
      >
        工作流默认模型
      </div>
      <p
        class="af-faint"
        style="margin: 0 0 12px; font-size: 12px; line-height: 1.6"
      >
        新建 llm 节点时继承这里的模型，节点自己的配置仍然优先——改默认值不会悄悄改掉已经跑通的节点。
      </p>

      <label class="af-label">模型来源</label>
      <select
        v-model="source"
        class="af-select"
        style="margin-bottom: 10px"
      >
        <option value="">
          进程默认端点（AF_LLM_*）
        </option>
        <option
          v-for="c in connections"
          :key="c.id"
          :value="c.id"
        >
          {{ c.name }}（连接）
        </option>
      </select>

      <label class="af-label">模型</label>
      <div style="display: flex; gap: 6px; margin-bottom: 6px">
        <input
          v-model="model"
          class="af-input mono"
          :placeholder="fallback || '例如 deepseek-chat'"
        >
        <select
          v-if="modelOptions.length"
          class="af-select"
          style="width: 180px"
          :value="''"
          @change="pickModel(($event.target as HTMLSelectElement).value)"
        >
          <option
            value=""
            disabled
          >
            选择清单里的模型
          </option>
          <option
            v-for="m in modelOptions"
            :key="m"
            :value="m"
          >
            {{ m }}
          </option>
        </select>
      </div>
      <ModelFactsChips
        :model="model"
        :items="catalog.items"
      />
      <p
        class="af-faint"
        style="margin: 6px 0 12px; font-size: 11px; line-height: 1.6"
      >
        <template v-if="source">
          该连接的默认模型：{{ fallback || '未设置' }}；模型名必须是这个端点认识的，否则调用会 400。
        </template>
        <template v-else-if="catalog.configured">
          服务端默认值：{{ catalog.defaultModel }}，可选 {{ catalog.models.length }} 个模型。
        </template>
        <template v-else>
          服务端还没配置真实模型端点（AF_LLM_*），保存后执行会明确报 LLM-NOT_CONFIGURED-001。
        </template>
      </p>

      <label
        v-if="llmNodeCount"
        class="af-faint"
        style="display: flex; gap: 6px; align-items: center; font-size: 12px; margin-bottom: 12px"
      >
        <input
          v-model="applyAll"
          type="checkbox"
        >
        同时应用到现有 {{ llmNodeCount }} 个 llm 节点
      </label>

      <div style="display: flex; gap: 8px; justify-content: flex-end">
        <button
          class="af-btn af-btn--sm"
          @click="emit('close')"
        >
          取消
        </button>
        <button
          class="af-btn af-btn--sm af-btn--primary"
          @click="save"
        >
          保存
        </button>
      </div>
    </div>
  </div>
</template>
