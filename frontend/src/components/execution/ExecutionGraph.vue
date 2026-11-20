<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { VueFlow } from '@vue-flow/core'
import type { Edge as FlowEdge, Node as FlowNode } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import WorkflowNode from '@/components/canvas/WorkflowNode.vue'
import { dslToFlow } from '@/dsl/flow-convert'
import { autoLayout } from '@/dsl/auto-layout'
import { aggregateStatus } from '@/dsl/exec-status'
import type { WorkflowDsl } from '@/dsl/types'
import type { NodeExecutionDto } from '@/types/api'

defineOptions({ name: 'ExecutionGraph' })

const props = defineProps<{
  dsl: WorkflowDsl | null
  nodes: NodeExecutionDto[]
  selectedKey: string | null
}>()

const emit = defineEmits<{ select: [nodeKey: string] }>()

// 冻结版本里存的是作者排的绝对坐标，长链会把图拉成一条线。执行图只做展示：
// 按拓扑折行排布，既不改动 DSL，也让 fitView 后卡片仍可读。
const graph = computed(() => {
  if (!props.dsl) return { nodes: [], edges: [] }
  const source = dslToFlow(props.dsl)
  return { nodes: autoLayout(source.nodes, source.edges), edges: source.edges }
})

const flowRef = ref<{ fitView?: (opts?: { padding?: number }) => Promise<boolean> } | null>(null)

function fitViewSoon(): void {
  void nextTick(() => {
    requestAnimationFrame(() => {
      void flowRef.value?.fitView?.({ padding: 0.15 })
    })
  })
}

const flowNodes = computed<FlowNode[]>(
  () =>
    graph.value.nodes.map((n) => {
      const row = props.nodes.find((r) => r.nodeKey === n.id)
      return {
        ...n,
        data: {
          ...n.data,
          readOnly: true,
          status: aggregateStatus(props.nodes, n.id),
          outputDigest: row?.outputDigest ?? null,
          errorCode: row?.errorCode ?? null,
          errorMessage: row?.errorMessage ?? null,
        },
      }
    }) as unknown as FlowNode[],
)

const flowEdges = computed<FlowEdge[]>(
  () =>
    graph.value.edges.map((e) => ({
      ...e,
      type: 'smoothstep',
      label: e.label,
    })) as unknown as FlowEdge[],
)

// 节点数据是异步到达的（要先取版本 DSL），初始化那次 fit 可能量不到尺寸。
watch(() => flowNodes.value.length, () => fitViewSoon())

function onNodeClick(e: unknown): void {
  const id = (e as { node?: { id?: string } }).node?.id
  if (id) emit('select', id)
}
</script>

<template>
  <div class="af-exec-graph">
    <div class="af-exec-graph__legend">
      <span>点节点看输入 / 输出</span>
      <span>强调=挂起/进行 · 中性=完成 · 危险=失败 · 灰=跳过</span>
    </div>
    <VueFlow
      ref="flowRef"
      :nodes="flowNodes"
      :edges="flowEdges"
      class="af-exec-graph__flow"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="true"
      :min-zoom="0.3"
      :fit-view-on-init="true"
      @nodes-initialized="fitViewSoon"
      @node-click="onNodeClick"
    >
      <Background
        :gap="24"
        pattern-color="var(--af-grid-line)"
      />
      <Controls />
      <template #node-af="nodeProps">
        <WorkflowNode
          v-bind="nodeProps"
          @expand="emit('select', nodeProps.id)"
        />
      </template>
    </VueFlow>
  </div>
</template>

<style scoped>
.af-exec-graph {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.af-exec-graph__legend {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--af-border);
  font-size: 11px;
  color: var(--af-text-faint);
}

.af-exec-graph__flow {
  flex: 1;
  min-height: 0;
}

:deep(.vue-flow__edge-path) {
  stroke: var(--af-border-strong);
  stroke-width: 1.6;
}

:deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--af-accent);
}

:deep(.vue-flow__edge-text) {
  fill: var(--af-text-dim);
  font-size: 11px;
}

:deep(.vue-flow__edge-textbg) {
  fill: var(--af-surface);
}
</style>
