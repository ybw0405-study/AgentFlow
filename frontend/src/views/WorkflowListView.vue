<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { workflowApi } from '@/api'
import { ApiError } from '@/api/http'
import { formatInstant } from '@/utils/instant'
import type { WorkflowDefinitionDto } from '@/types/api'
import BlueprintBoard from '@/components/design/BlueprintBoard.vue'
import DesignIcon from '@/components/design/DesignIcon.vue'
import IsoInstrument from '@/components/design/IsoInstrument.vue'
import SectionHead from '@/components/design/SectionHead.vue'
import TickerBar from '@/components/design/TickerBar.vue'

const tickerItems = ['DSL FIRST', 'IDEMPOTENT RUNS', 'HUMAN GATES', 'BUDGET LEDGER', 'FULL TRACE']
const nodeKinds = [
  { code: 'llm', icon: 'llm', title: '模型推理', desc: '统一接入多家模型，能力与预算同屏可见。' },
  { code: 'retrieval', icon: 'retrieval', title: '知识检索', desc: '段落级召回，结果进入可引用的上下文。' },
  { code: 'branch', icon: 'branch', title: '条件分支', desc: '按表达式分路，复杂流程保持可读。' },
  { code: 'loop', icon: 'loop', title: '循环与批处理', desc: '受上限约束的重复执行，防止跑飞。' },
  { code: 'human', icon: 'human', title: '人工审批', desc: '高风险动作停在人这一侧再继续。' },
  { code: 'tool', icon: 'tool', title: '工具调用', desc: 'MCP 工具以最小权限挂接到节点。' },
] as const

const router = useRouter()
const items = ref<WorkflowDefinitionDto[]>([])
const keyword = ref('')
const status = ref('')
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const creating = ref(false)
const newName = ref('')

async function load(reset = true) {
  loading.value = true
  error.value = null
  try {
    const page = await workflowApi.list({
      status: status.value || undefined,
      keyword: keyword.value || undefined,
      cursor: reset ? undefined : (nextCursor.value ?? undefined),
    })
    items.value = reset ? page.items : [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '加载失败'
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!newName.value.trim()) return
  creating.value = true
  try {
    const { id } = await workflowApi.create(newName.value.trim())
    void router.push({ name: 'editor', params: { id } })
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '创建失败'
  } finally {
    creating.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="af-page af-show-page">
    <section class="af-show-hero">
      <div class="af-show-hero__grid">
        <div class="af-reveal is-in">
          <span class="af-fig-tag">FIG. 01 / WORKFLOW REGISTRY</span>
          <h1 class="af-display">
            Work<span class="bl">flow</span>
            <span class="af-display__zh">工作流登记台</span>
          </h1>
          <p class="af-show-sub">
            每条流程都是一张可复现的图纸：节点、连线、预算与审批在同一份 DSL 里对齐。
          </p>
          <div class="af-stats">
            <div class="af-stat">
              <b>{{ items.length }}<em>条</em></b>
              <span>REGISTERED</span>
            </div>
            <div class="af-stat">
              <b>{{ items.filter((item) => item.status === 'PUBLISHED').length }}<em>条</em></b>
              <span>PUBLISHED</span>
            </div>
            <div class="af-stat">
              <b>06<em>类</em></b>
              <span>NODE KINDS</span>
            </div>
          </div>
        </div>
        <div class="af-reveal is-in">
          <BlueprintBoard />
        </div>
      </div>
    </section>

    <TickerBar :items="tickerItems" />

    <SectionHead
      no="01"
      title="REGISTRY"
      zh="流程图纸目录"
      aside="查询、新建与发布都从这里进入。状态只保留草稿 / 已发布 / 已归档三种。"
    />

    <div style="display: flex; gap: 8px; margin-bottom: 16px">
      <input
        v-model="keyword"
        class="af-input"
        style="max-width: 260px"
        placeholder="搜索名称/描述"
        @keyup.enter="load()"
      >
      <select
        v-model="status"
        class="af-select"
        style="width: 140px"
        @change="load()"
      >
        <option value="">
          全部状态
        </option>
        <option value="DRAFT">
          DRAFT
        </option>
        <option value="PUBLISHED">
          PUBLISHED
        </option>
        <option value="ARCHIVED">
          ARCHIVED
        </option>
      </select>
      <button
        class="af-btn"
        :disabled="loading"
        @click="load()"
      >
        查询
      </button>
      <div class="af-spacer" />
      <input
        v-model="newName"
        class="af-input"
        style="max-width: 220px"
        placeholder="新工作流名称"
        @keyup.enter="create"
      >
      <button
        class="af-btn af-btn--primary"
        :disabled="creating || !newName.trim()"
        @click="create"
      >
        新建
      </button>
    </div>

    <div
      v-if="error"
      class="af-error-box"
      style="margin-bottom: 12px"
    >
      {{ error }}
    </div>

    <div class="af-panel">
      <table class="af-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>状态</th>
            <th>当前版本</th>
            <th>更新时间</th>
            <th style="width: 200px">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="wf in items"
            :key="wf.id"
          >
            <td>
              <router-link :to="{ name: 'editor', params: { id: wf.id } }">{{ wf.name }}</router-link>
              <div
                v-if="wf.description"
                class="af-faint"
                style="font-size: 11px"
              >
                {{ wf.description }}
              </div>
            </td>
            <td>
              <span
                class="af-status"
                :class="{ 'af-status--running': wf.status === 'PUBLISHED' }"
              >
                {{ wf.status }}
              </span>
            </td>
            <td class="mono af-dim">
              {{ wf.currentVersionId ? '已发布' : '—' }}
            </td>
            <td class="mono af-dim">
              {{ formatInstant(wf.updatedAt) }}
            </td>
            <td>
              <router-link :to="{ name: 'editor', params: { id: wf.id } }">
                <button class="af-btn af-btn--sm">画布</button>
              </router-link>
              <router-link :to="{ name: 'versions', params: { id: wf.id } }">
                <button
                  class="af-btn af-btn--sm"
                  style="margin-left: 6px"
                >版本</button>
              </router-link>
              <router-link :to="{ name: 'executions', query: { definitionId: wf.id } }">
                <button
                  class="af-btn af-btn--sm"
                  style="margin-left: 6px"
                >执行</button>
              </router-link>
            </td>
          </tr>
          <tr v-if="!items.length && !loading">
            <td
              colspan="5"
              class="af-faint"
              style="text-align: center; padding: 24px"
            >
              暂无工作流
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="nextCursor"
      style="margin-top: 12px"
    >
      <button
        class="af-btn"
        :disabled="loading"
        @click="load(false)"
      >
        加载更多
      </button>
    </div>

    <SectionHead
      no="02"
      title="NODE LIBRARY"
      zh="节点库"
      aside="六类节点覆盖推理、检索、分支、循环、审批与工具调用。"
    />
    <div class="af-nodelib">
      <article
        v-for="node in nodeKinds"
        :key="node.code"
        class="af-ncard af-reveal"
      >
        <div class="af-ncard__ico"><DesignIcon :name="node.icon" /></div>
        <div>
          <h3>{{ node.title }}<code>{{ node.code }}</code></h3>
          <p>{{ node.desc }}</p>
        </div>
      </article>
    </div>

    <div class="af-band af-reveal">
      <div class="af-band__inner">
        <div class="af-band__cell">
          <b>1<em>份</em></b><span>DSL / SINGLE SOURCE</span>
        </div>
        <div class="af-band__cell">
          <b>3<em>色</em></b><span>STATE SEMANTICS</span>
        </div>
        <div class="af-band__cell">
          <b>0<em>次</em></b><span>UNTRACED EXECUTION</span>
        </div>
        <div class="af-band__cell">
          <b>∞<em>可复现</em></b><span>REPLAYABLE RUNS</span>
        </div>
      </div>
    </div>

    <div class="af-show-hero" style="margin-top: 40px">
      <div class="af-show-hero__grid">
        <div class="af-reveal">
          <span class="af-fig-tag">FIG. 02 / RUNTIME INSTRUMENT</span>
          <h2 class="af-section-head__title" style="margin-top: 14px">
            INSTRUMENT<span class="af-section-head__zh">执行仪器剖面</span>
          </h2>
          <p class="af-show-sub">
            画布、执行图与 DSL 是同一对象的三种视图；倾斜观察时仍能读出每条边的方向。
          </p>
        </div>
        <div class="af-reveal"><IsoInstrument /></div>
      </div>
    </div>
  </div>
</template>
