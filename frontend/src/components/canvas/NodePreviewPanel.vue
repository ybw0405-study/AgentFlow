<script setup lang="ts">
import { computed, ref } from 'vue'
import { workflowApi } from '@/api'
import { errorText } from '@/api/errors'
import { initialVariables } from '@/dsl/run-defaults'
import { missingRequired } from '@/dsl/run-input'
import type { NodePreviewResult } from '@/types/api'
import type { NodeType, VariableSpec, WorkflowDsl } from '@/dsl/types'
import RunVariablesForm from './RunVariablesForm.vue'

defineOptions({ name: 'NodePreviewPanel' })

const props = defineProps<{
  workflowId: string
  nodeKey: string
  nodeType: NodeType
  /** 画布当前草稿：试跑跑的就是眼前这份配置。 */
  dsl: WorkflowDsl
  variables: Record<string, VariableSpec>
}>()

const emit = defineEmits<{ close: [] }>()

const values = ref<Record<string, unknown>>(initialVariables(props.variables))
const busy = ref(false)
const error = ref<string | null>(null)
const result = ref<NodePreviewResult | null>(null)

const missing = computed(() => missingRequired(props.variables, values.value))
const outputText = computed(() =>
  result.value?.output ? JSON.stringify(result.value.output, null, 2) : '',
)
const inputText = computed(() =>
  result.value?.input ? JSON.stringify(result.value.input, null, 2) : '',
)
const ok = computed(() => result.value?.status === 'SUCCEEDED' && !result.value?.errorCode)
const model = computed(() => {
  const output = result.value?.output as Record<string, unknown> | null
  const name = output?.model
  return typeof name === 'string' ? name : ''
})

async function run(): Promise<void> {
  if (missing.value.length) {
    error.value = `必填项还没填：${missing.value.join('、')}`
    return
  }
  busy.value = true
  error.value = null
  try {
    result.value = await workflowApi.previewNode(props.workflowId, props.nodeKey, {
      dsl: props.dsl,
      variables: values.value,
    })
  } catch (e) {
    result.value = null
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div
    class="af-modal-backdrop"
    @click.self="emit('close')"
  >
    <div
      class="af-panel af-modal"
      style="width: 760px; max-height: 88vh; overflow: auto"
    >
      <div style="display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px">
        <div
          class="af-page-title"
          style="margin: 0"
        >
          试跑
        </div>
        <span class="mono af-dim">{{ nodeKey }}</span>
        <span class="af-status">{{ nodeType }}</span>
        <div class="af-spacer" />
        <button
          class="af-btn af-btn--sm"
          @click="emit('close')"
        >
          关闭
        </button>
      </div>
      <p
        class="af-faint"
        style="margin: 0 0 10px; font-size: 12px; line-height: 1.6"
      >
        会把该节点以及它依赖的上游真实跑一遍（模型、检索、连接器都会实际调用），
        然后返回这个节点的输入与输出。试跑不产生执行记录，也不改动已发布版本。
      </p>

      <RunVariablesForm
        v-model="values"
        :variables="variables"
      />

      <div style="display: flex; align-items: center; gap: 10px; margin-top: 12px">
        <button
          class="af-btn af-btn--sm af-btn--primary"
          :disabled="busy"
          @click="run"
        >
          {{ busy ? '试跑中…' : '试跑到这里' }}
        </button>
        <span
          v-if="missing.length"
          class="af-faint"
          style="font-size: 11px"
        >
          必填：{{ missing.join('、') }}
        </span>
        <span
          v-else
          class="af-faint"
          style="font-size: 11px"
        >
          上游有模型节点时会真实计费
        </span>
      </div>

      <div
        v-if="error"
        class="af-error-box"
        style="margin-top: 12px; line-height: 1.6"
      >
        {{ error }}
      </div>

      <template v-if="result">
        <div class="af-preview__summary">
          <span
            class="af-status"
            :class="{ 'af-status--running': ok }"
          >
            {{ result.status }} · {{ result.nodeStatus }}
          </span>
          <span class="mono af-faint">耗时 {{ result.latencyMs ?? '—' }} ms</span>
          <span class="mono af-faint">节点 token {{ result.tokenUsed }}</span>
          <span class="mono af-faint">本次共 {{ result.totalTokens }}</span>
          <span
            v-if="model"
            class="mono af-dim"
          >模型 {{ model }}</span>
        </div>

        <div
          v-if="result.errorCode"
          class="af-error-box"
          style="margin-top: 8px; line-height: 1.6"
        >
          <strong>{{ result.errorCode }}</strong>
          <span v-if="result.errorMessage"> · {{ result.errorMessage }}</span>
        </div>

        <div class="af-preview__grid">
          <div>
            <div class="af-label">
              节点输出
            </div>
            <pre class="af-preview__code">{{ outputText || '（没有输出）' }}</pre>
          </div>
          <div>
            <div class="af-label">
              节点输入
            </div>
            <pre class="af-preview__code">{{ inputText || '（没有记录到输入快照）' }}</pre>
          </div>
        </div>

        <div class="af-label">
          链路
        </div>
        <div class="af-preview__trace">
          <div
            v-for="step in result.trace"
            :key="step.key"
            class="af-preview__step"
            :class="{ 'af-preview__step--target': step.key === result.nodeKey }"
          >
            <span class="mono">{{ step.key }}</span>
            <span class="af-faint">{{ step.status }}</span>
            <span class="mono af-faint">{{ step.tokenUsed }} token</span>
            <span class="mono af-faint">{{ step.latencyMs ?? '—' }} ms</span>
            <span
              v-if="step.errorCode"
              class="mono af-danger"
            >{{ step.errorCode }}</span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.af-preview__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  font-size: 12px;
}

.af-preview__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 4px;
}

.af-preview__code {
  margin: 0;
  padding: 8px;
  max-height: 240px;
  overflow: auto;
  background: var(--af-bg);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  font-family: var(--af-font-mono);
  font-size: 11.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.af-preview__trace {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.af-preview__step {
  display: grid;
  grid-template-columns: 1fr 80px 90px 80px auto;
  gap: 8px;
  padding: 3px 6px;
  border-radius: var(--af-radius-sm);
  font-size: 11.5px;
}

.af-preview__step--target {
  background: var(--af-accent-dim);
}
</style>
