<script setup lang="ts">
import { computed } from 'vue'
import {
  factsSummary,
  maxTokensSuggestion,
  modelFacts,
  tagLabel,
} from '@/dsl/model-capabilities'
import type { ModelFactsDto } from '@/types/api'

defineOptions({ name: 'ModelFactsChips' })

const props = defineProps<{
  /** 当前选中的模型 id；空表示还没选。 */
  model: string
  /** 引擎随模型清单给回的能力标注，没有就本地按模型名推断。 */
  items?: ModelFactsDto[] | null
  /** 当前节点的 maxTokens；给了才会算「是不是该调大」。 */
  currentMaxTokens?: number | null
}>()

const emit = defineEmits<{ 'apply-max-tokens': [value: number] }>()

const facts = computed(() => modelFacts(props.model, props.items))
const summary = computed(() => factsSummary(facts.value))
const suggestion = computed(() => maxTokensSuggestion(facts.value))
/** 只在「明确小于建议值」时提醒，已经调大的不再啰嗦。 */
const shouldRaise = computed(
  () => suggestion.value !== null
    && typeof props.currentMaxTokens === 'number'
    && props.currentMaxTokens < suggestion.value,
)
</script>

<template>
  <div
    v-if="facts && (facts.tags.length || summary)"
    class="af-facts"
  >
    <span
      v-for="tag in facts.tags"
      :key="tag"
      class="af-facts__tag"
      :class="{ 'af-facts__tag--warn': tag === 'reasoning' }"
    >
      {{ tagLabel(tag) }}
    </span>
    <span
      v-if="summary"
      class="af-facts__meta mono"
    >{{ summary }}</span>
    <span
      v-if="facts.source === 'inferred' && facts.tags.length"
      class="af-facts__meta"
      title="上游 /models 没有给出能力字段，这里是按模型名推断的"
    >推断</span>
  </div>
  <p
    v-if="shouldRaise"
    class="af-facts__hint"
  >
    推理型模型会先把 token 花在思考上，maxTokens 太小容易返回空内容。
    <button
      class="af-facts__btn"
      @click="emit('apply-max-tokens', suggestion as number)"
    >
      设为 {{ suggestion }}
    </button>
  </p>
</template>

<style scoped>
.af-facts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.af-facts__tag {
  padding: 1px 6px;
  border-radius: var(--af-radius-sm);
  background: var(--af-accent-dim);
  color: var(--af-accent);
  font-size: 10.5px;
  white-space: nowrap;
}

.af-facts__tag--warn {
  background: var(--af-panel);
  color: var(--af-text-dim);
  border: 1px solid var(--af-border);
}

.af-facts__meta {
  font-size: 10.5px;
  color: var(--af-text-faint);
}

.af-facts__hint {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--af-text-dim);
}

.af-facts__btn {
  margin-left: 4px;
  padding: 1px 6px;
  background: transparent;
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  color: var(--af-accent);
  font-size: 10.5px;
  cursor: pointer;
}

.af-facts__btn:hover {
  border-color: var(--af-accent-border);
}
</style>
