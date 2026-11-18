<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { evalApi, workflowApi } from '@/api'
import { ApiError } from '@/api/http'
import { errorText } from '@/api/errors'
import type {
  EvalCaseDto,
  EvalDatasetDto,
  EvalRunDetail,
  EvalRunDto,
  ExperimentDto,
  WorkflowDefinitionDto,
  WorkflowVersionDto,
} from '@/types/api'

const datasets = ref<EvalDatasetDto[]>([])
const selectedId = ref<string>('')
const cases = ref<EvalCaseDto[]>([])
const runs = ref<EvalRunDto[]>([])
const workflows = ref<WorkflowDefinitionDto[]>([])
const versions = ref<WorkflowVersionDto[]>([])
const runDetail = ref<EvalRunDetail | null>(null)
const experiments = ref<ExperimentDto[]>([])

const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)
let timer: number | undefined

const datasetForm = reactive({ name: '', description: '' })
const caseForm = reactive({
  name: '',
  input: '{\n  "question": "AgentFlow 如何保证恰好一次？"\n}',
  expected: '',
  mode: 'EXISTS',
  path: 'answer',
  min: '',
})
const runForm = reactive({ definitionId: '', versionId: '' })
const abForm = reactive({ name: '', versionA: '', versionB: '' })

const selectedDataset = computed(
  () => datasets.value.find((d) => d.id === selectedId.value) ?? null,
)
const activeRun = computed(() => runDetail.value?.run ?? null)
const experimentViews = computed(() =>
  experiments.value.map((e) => ({ ...e, summary: parseSummary(e.summaryJson) })),
)

function describe(e: unknown): string {
  return errorText(e)
}

function parseSummary(raw?: string): Record<string, unknown> | null {
  if (!raw) return null
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

function parseJson(text: string, label: string): unknown {
  const trimmed = text.trim()
  if (!trimmed) return undefined
  try {
    return JSON.parse(trimmed)
  } catch {
    throw new Error(`${label} 不是合法 JSON`)
  }
}

async function loadDatasets(): Promise<void> {
  try {
    datasets.value = await evalApi.datasets()
    if (!selectedId.value && datasets.value.length) {
      await selectDataset(datasets.value[0]!.id)
    }
  } catch (e) {
    error.value = describe(e)
  }
}

async function selectDataset(id: string): Promise<void> {
  selectedId.value = id
  runDetail.value = null
  try {
    const detail = await evalApi.dataset(id)
    cases.value = detail.cases
    runs.value = detail.runs
  } catch (e) {
    error.value = describe(e)
  }
}

async function createDataset(): Promise<void> {
  if (!datasetForm.name.trim()) return
  busy.value = true
  error.value = null
  try {
    const created = await evalApi.createDataset({
      name: datasetForm.name.trim(),
      description: datasetForm.description.trim() || undefined,
    })
    notice.value = `已创建数据集 ${created.dataset.name}`
    datasetForm.name = ''
    datasetForm.description = ''
    await loadDatasets()
    await selectDataset(created.dataset.id)
  } catch (e) {
    error.value = describe(e)
  } finally {
    busy.value = false
  }
}

async function addCase(): Promise<void> {
  if (!selectedId.value || !caseForm.name.trim()) return
  busy.value = true
  error.value = null
  try {
    const input = (parseJson(caseForm.input, '输入') ?? {}) as Record<string, unknown>
    const expected = parseJson(caseForm.expected, '期望')
    const assertion: Record<string, unknown> = { mode: caseForm.mode }
    if (caseForm.mode === 'EXISTS' || caseForm.mode === 'NUMERIC') {
      assertion.path = caseForm.path.trim()
    }
    if (caseForm.mode === 'NUMERIC' && caseForm.min.trim()) {
      assertion.min = Number(caseForm.min)
    }
    const detail = await evalApi.addCase(selectedId.value, {
      name: caseForm.name.trim(),
      input,
      expected,
      assertion,
    })
    cases.value = detail.cases
    caseForm.name = ''
    notice.value = '用例已添加'
  } catch (e) {
    error.value = e instanceof Error && !(e instanceof ApiError) ? e.message : describe(e)
  } finally {
    busy.value = false
  }
}

async function removeCase(row: EvalCaseDto): Promise<void> {
  if (!window.confirm(`删除用例「${row.name}」？`)) return
  try {
    await evalApi.removeCase(row.id)
    if (selectedId.value) await selectDataset(selectedId.value)
  } catch (e) {
    error.value = describe(e)
  }
}

async function loadWorkflows(): Promise<void> {
  try {
    const page = await workflowApi.list({ limit: 100 })
    workflows.value = page.items
    if (!runForm.definitionId && workflows.value.length) {
      runForm.definitionId = workflows.value[0]!.id
      await loadVersions()
    }
  } catch (e) {
    error.value = describe(e)
  }
}

async function loadVersions(): Promise<void> {
  versions.value = []
  runForm.versionId = ''
  abForm.versionA = ''
  abForm.versionB = ''
  if (!runForm.definitionId) return
  try {
    const detail = await workflowApi.detail(runForm.definitionId)
    versions.value = detail.versions
  } catch (e) {
    error.value = describe(e)
  }
}

async function startRun(): Promise<void> {
  if (!selectedId.value || !runForm.definitionId) return
  busy.value = true
  error.value = null
  try {
    const detail = await evalApi.startRun(selectedId.value, {
      definitionId: runForm.definitionId,
      versionId: runForm.versionId || undefined,
    })
    runDetail.value = detail
    runs.value = await evalApi.runs(selectedId.value)
    notice.value = '评测已启动，结果会随执行落库自动刷新'
    ensurePolling()
  } catch (e) {
    error.value = describe(e)
  } finally {
    busy.value = false
  }
}

async function openRun(run: EvalRunDto): Promise<void> {
  try {
    runDetail.value = await evalApi.run(run.id)
    ensurePolling()
  } catch (e) {
    error.value = describe(e)
  }
}

async function refreshRun(): Promise<void> {
  if (!runDetail.value) return
  const detail = await evalApi.run(runDetail.value.run.id)
  runDetail.value = detail
  if (selectedId.value) runs.value = await evalApi.runs(selectedId.value)
  if (detail.run.status !== 'RUNNING') stopPolling()
}

function ensurePolling(): void {
  stopPolling()
  if (runDetail.value?.run.status !== 'RUNNING') return
  timer = window.setInterval(() => {
    void refreshRun()
  }, 3000)
}

function stopPolling(): void {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function startExperiment(): Promise<void> {
  if (!selectedId.value || !runForm.definitionId) return
  if (!abForm.versionA || !abForm.versionB) {
    error.value = 'A/B 需要选择两个版本'
    return
  }
  busy.value = true
  error.value = null
  try {
    await evalApi.createExperiment({
      definitionId: runForm.definitionId,
      datasetId: selectedId.value,
      name: abForm.name.trim() || 'ab-experiment',
      versionA: abForm.versionA,
      versionB: abForm.versionB,
    })
    notice.value = 'A/B 实验已启动，两版并行跑同一数据集'
    abForm.name = ''
    await loadExperiments()
  } catch (e) {
    error.value = describe(e)
  } finally {
    busy.value = false
  }
}

async function loadExperiments(): Promise<void> {
  try {
    experiments.value = await evalApi.experiments(runForm.definitionId || undefined)
  } catch (e) {
    error.value = describe(e)
  }
}

function versionLabel(v: WorkflowVersionDto): string {
  return `v${v.versionNo} · ${v.id.slice(0, 8)}`
}

onMounted(async () => {
  await loadWorkflows()
  await loadDatasets()
  await loadExperiments()
})

onUnmounted(stopPolling)
</script>

<template>
  <div class="af-page">
    <h1 class="af-page-title">
      评测
    </h1>
    <p
      class="af-faint"
      style="margin-bottom: 12px; font-size: 12px"
    >
      评测跑的就是线上同一套引擎与版本快照；断言失败会记录期望/实际，便于定位是模型问题还是用例问题。
    </p>

    <div
      v-if="error"
      class="af-error-box"
      style="margin-bottom: 12px"
    >
      {{ error }}
    </div>
    <div
      v-if="notice"
      class="af-panel"
      style="margin-bottom: 12px; padding: 10px"
    >
      {{ notice }}
    </div>

    <div
      class="af-panel"
      style="margin-bottom: 12px; padding: 12px"
    >
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <label
          class="af-dim"
          style="width: 96px"
        >数据集</label>
        <select
          class="af-select"
          style="max-width: 320px"
          :value="selectedId"
          @change="selectDataset(($event.target as HTMLSelectElement).value)"
        >
          <option value="">
            选择数据集
          </option>
          <option
            v-for="d in datasets"
            :key="d.id"
            :value="d.id"
          >
            {{ d.name }}
          </option>
        </select>
        <input
          v-model="datasetForm.name"
          class="af-input"
          style="max-width: 200px"
          placeholder="新建数据集名"
        >
        <input
          v-model="datasetForm.description"
          class="af-input"
          style="max-width: 240px"
          placeholder="描述（可选）"
        >
        <button
          class="af-btn af-btn--primary af-btn--sm"
          :disabled="busy"
          @click="createDataset"
        >
          创建
        </button>
      </div>
      <p
        v-if="selectedDataset"
        class="af-faint"
        style="font-size: 12px"
      >
        {{ selectedDataset.description || '无描述' }}
      </p>
    </div>

    <div
      class="af-panel"
      style="margin-bottom: 12px"
    >
      <div style="padding: 12px 12px 0">
        <h2 style="font-size: 13px; margin: 0 0 8px">
          用例（{{ cases.length }}）
        </h2>
      </div>
      <table class="af-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>断言</th>
            <th>输入</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="c in cases"
            :key="c.id"
          >
            <td>{{ c.name }}</td>
            <td class="mono">
              {{ c.assertionJson }}
            </td>
            <td
              class="mono"
              style="max-width: 320px; overflow: hidden; text-overflow: ellipsis"
            >
              {{ c.inputJson }}
            </td>
            <td>
              <button
                class="af-btn af-btn--sm"
                @click="removeCase(c)"
              >
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!cases.length">
            <td
              colspan="4"
              class="af-faint"
            >
              还没有用例。
            </td>
          </tr>
        </tbody>
      </table>
      <div style="padding: 12px">
        <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
          <label
            class="af-dim"
            style="width: 72px"
          >用例名</label>
          <input
            v-model="caseForm.name"
            class="af-input"
            placeholder="greeting-zh"
            style="max-width: 220px"
          >
          <label class="af-dim">断言</label>
          <select
            v-model="caseForm.mode"
            class="af-select"
            style="max-width: 140px"
          >
            <option value="EXISTS">
              EXISTS
            </option>
            <option value="EXACT">
              EXACT
            </option>
            <option value="CONTAINS">
              CONTAINS
            </option>
            <option value="NUMERIC">
              NUMERIC
            </option>
          </select>
          <input
            v-if="caseForm.mode === 'EXISTS' || caseForm.mode === 'NUMERIC'"
            v-model="caseForm.path"
            class="af-input"
            style="max-width: 140px"
            placeholder="path"
          >
          <input
            v-if="caseForm.mode === 'NUMERIC'"
            v-model="caseForm.min"
            class="af-input"
            style="max-width: 90px"
            placeholder="min"
          >
        </div>
        <div style="display: flex; gap: 8px; margin-bottom: 8px">
          <textarea
            v-model="caseForm.input"
            class="af-input"
            rows="4"
            style="flex: 1"
            placeholder="输入变量 JSON"
          />
          <textarea
            v-model="caseForm.expected"
            class="af-input"
            rows="4"
            style="flex: 1"
            placeholder="期望结果 JSON（EXACT/CONTAINS 用）"
          />
        </div>
        <button
          class="af-btn af-btn--primary af-btn--sm"
          :disabled="busy"
          @click="addCase"
        >
          添加用例
        </button>
      </div>
    </div>

    <div
      class="af-panel"
      style="margin-bottom: 12px; padding: 12px"
    >
      <h2 style="font-size: 13px; margin: 0 0 8px">
        运行评测
      </h2>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <select
          v-model="runForm.definitionId"
          class="af-select"
          style="max-width: 260px"
          @change="loadVersions"
        >
          <option value="">
            选择工作流
          </option>
          <option
            v-for="w in workflows"
            :key="w.id"
            :value="w.id"
          >
            {{ w.name }}
          </option>
        </select>
        <select
          v-model="runForm.versionId"
          class="af-select"
          style="max-width: 220px"
        >
          <option value="">
            当前发布版本
          </option>
          <option
            v-for="v in versions"
            :key="v.id"
            :value="v.id"
          >
            {{ versionLabel(v) }}
          </option>
        </select>
        <button
          class="af-btn af-btn--primary af-btn--sm"
          :disabled="busy || !selectedId"
          @click="startRun"
        >
          开始评测
        </button>
        <button
          class="af-btn af-btn--sm"
          :disabled="!activeRun"
          @click="refreshRun"
        >
          刷新结果
        </button>
      </div>

      <table class="af-table">
        <thead>
          <tr>
            <th>运行</th>
            <th>状态</th>
            <th>通过 / 总数</th>
            <th>版本</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in runs"
            :key="r.id"
            style="cursor: pointer"
            @click="openRun(r)"
          >
            <td class="mono">
              {{ r.id.slice(0, 8) }}
            </td>
            <td>{{ r.status }}</td>
            <td>{{ r.passed }} / {{ r.total }}</td>
            <td class="mono">
              {{ r.versionId.slice(0, 8) }}
            </td>
            <td>{{ r.createdAt }}</td>
          </tr>
          <tr v-if="!runs.length">
            <td
              colspan="5"
              class="af-faint"
            >
              还没有运行记录。
            </td>
          </tr>
        </tbody>
      </table>

      <div
        v-if="runDetail"
        style="margin-top: 12px"
      >
        <h3 style="font-size: 12px; margin: 0 0 6px">
          运行 {{ runDetail.run.id.slice(0, 8) }} · {{ runDetail.run.status }}
          （通过 {{ runDetail.run.passed }} / {{ runDetail.run.total }}）
        </h3>
        <table class="af-table">
          <thead>
            <tr>
              <th>用例</th>
              <th>结果</th>
              <th>耗时</th>
              <th>token</th>
              <th>实际 / 错误</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in runDetail.results"
              :key="r.id"
            >
              <td>{{ r.caseName }}</td>
              <td>{{ r.status }}</td>
              <td>{{ r.latencyMs ?? '—' }}</td>
              <td>{{ r.tokenUsed ?? '—' }}</td>
              <td
                class="mono"
                style="max-width: 360px; word-break: break-all"
              >
                {{ r.errorMessage || JSON.stringify(r.actual) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div
      class="af-panel"
      style="padding: 12px"
    >
      <h2 style="font-size: 13px; margin: 0 0 8px">
        A/B 对比（同一数据集，两个版本）
      </h2>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
        <input
          v-model="abForm.name"
          class="af-input"
          style="max-width: 200px"
          placeholder="实验名"
        >
        <select
          v-model="abForm.versionA"
          class="af-select"
          style="max-width: 200px"
        >
          <option value="">
            版本 A
          </option>
          <option
            v-for="v in versions"
            :key="v.id"
            :value="v.id"
          >
            {{ versionLabel(v) }}
          </option>
        </select>
        <select
          v-model="abForm.versionB"
          class="af-select"
          style="max-width: 200px"
        >
          <option value="">
            版本 B
          </option>
          <option
            v-for="v in versions"
            :key="v.id"
            :value="v.id"
          >
            {{ versionLabel(v) }}
          </option>
        </select>
        <button
          class="af-btn af-btn--primary af-btn--sm"
          :disabled="busy || !selectedId"
          @click="startExperiment"
        >
          开始对比
        </button>
        <button
          class="af-btn af-btn--sm"
          @click="loadExperiments"
        >
          刷新
        </button>
      </div>

      <table class="af-table">
        <thead>
          <tr>
            <th>实验</th>
            <th>状态</th>
            <th>通过率 A → B</th>
            <th>胜出</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="e in experimentViews"
            :key="e.id"
          >
            <td>{{ e.name }}</td>
            <td>{{ e.status }}</td>
            <td>
              {{ e.summary?.passRateA ?? '—' }} → {{ e.summary?.passRateB ?? '—' }}
            </td>
            <td>{{ e.summary?.winner ?? '—' }}</td>
          </tr>
          <tr v-if="!experimentViews.length">
            <td
              colspan="4"
              class="af-faint"
            >
              还没有实验。需要先冻结并发布两个版本。
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
