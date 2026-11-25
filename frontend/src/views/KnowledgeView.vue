<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorText } from '@/api/errors'
import { knowledgeApi, workflowApi } from '@/api'
import type { WorkflowDsl } from '@/dsl/types'
import type {
  KnowledgeBaseDto,
  KnowledgeDocumentDto,
  KnowledgeHitDto,
} from '@/types/api'

defineOptions({ name: 'KnowledgeView' })

const router = useRouter()
const bases = ref<KnowledgeBaseDto[]>([])
const selectedId = ref('')
const documents = ref<KnowledgeDocumentDto[]>([])
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)

const newName = ref('')
const newDesc = ref('')
const docTitle = ref('')
const docContent = ref('')
const docSource = ref('TEXT')
const query = ref('')
const hits = ref<KnowledgeHitDto[]>([])
const searchMeta = ref<{ matched: number; scanned: number } | null>(null)

const selected = computed(() => bases.value.find((b) => b.id === selectedId.value) ?? null)

async function loadBases(): Promise<void> {
  try {
    bases.value = await knowledgeApi.bases()
    if (!selectedId.value || !bases.value.some((b) => b.id === selectedId.value)) {
      selectedId.value = bases.value[0]?.id ?? ''
    }
    await loadDocuments()
  } catch (e) {
    error.value = errorText(e)
  }
}

async function loadDocuments(): Promise<void> {
  if (!selectedId.value) {
    documents.value = []
    return
  }
  try {
    documents.value = await knowledgeApi.documents(selectedId.value)
  } catch (e) {
    error.value = errorText(e)
  }
}

async function createBase(): Promise<void> {
  if (!newName.value.trim()) return
  busy.value = true
  error.value = null
  try {
    const created = await knowledgeApi.createBase(newName.value.trim(), newDesc.value.trim() || undefined)
    newName.value = ''
    newDesc.value = ''
    notice.value = `已创建「${created.name}」，接下来导入文档`
    await loadBases()
    selectedId.value = created.id
    await loadDocuments()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function removeBase(id: string): Promise<void> {
  if (!window.confirm('删除知识库会同时删除其中的文档与切片，确定继续？')) return
  busy.value = true
  try {
    await knowledgeApi.removeBase(id)
    selectedId.value = ''
    hits.value = []
    await loadBases()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function addDocument(): Promise<void> {
  if (!selectedId.value || !docContent.value.trim()) return
  busy.value = true
  error.value = null
  try {
    const doc = await knowledgeApi.addDocument(selectedId.value, {
      title: docTitle.value.trim() || undefined,
      content: docContent.value,
      source: docSource.value,
    })
    docTitle.value = ''
    docContent.value = ''
    docSource.value = 'TEXT'
    notice.value = `已导入「${doc.title}」，切成 ${doc.chunkCount} 个片段，现在可以试检索了`
    await Promise.all([loadBases(), loadDocuments()])
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function onFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    docContent.value = String(reader.result ?? '')
    if (!docTitle.value) docTitle.value = file.name
    docSource.value = 'FILE'
    notice.value = `已读取 ${file.name}，点「导入文档」写入知识库`
  }
  reader.readAsText(file, 'UTF-8')
  input.value = ''
}

async function removeDocument(id: string): Promise<void> {
  busy.value = true
  try {
    await knowledgeApi.removeDocument(id)
    await Promise.all([loadBases(), loadDocuments()])
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function testSearch(): Promise<void> {
  if (!selectedId.value || !query.value.trim()) return
  busy.value = true
  error.value = null
  try {
    const result = await knowledgeApi.search({
      knowledgeBaseIds: [selectedId.value],
      query: query.value.trim(),
      topK: 5,
    })
    hits.value = result.results
    searchMeta.value = { matched: result.matchedChunks, scanned: result.scannedChunks }
    if (!result.results.length) notice.value = '没有命中片段：换个问法，或确认文档里确实有相关内容'
    else notice.value = null
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/** 一键生成引用该知识库的问答工作流，避免用户手抄 UUID 到 DSL。 */
async function createWorkflow(): Promise<void> {
  if (!selected.value) return
  busy.value = true
  error.value = null
  const dsl: WorkflowDsl = {
    dslVersion: '1.0',
    variables: { question: { type: 'string', required: true } },
    nodes: [
      { key: 'start', type: 'start', name: '入口', position: { x: 60, y: 180 }, config: {} },
      {
        key: 'retrieve',
        type: 'retrieval',
        name: '文档检索',
        position: { x: 320, y: 180 },
        config: {
          knowledgeBaseIds: [selected.value.id],
          queryTemplate: '{{ vars.question }}',
          topK: 5,
          minScore: 0,
        },
      },
      {
        key: 'answer',
        type: 'llm',
        name: '生成回答',
        position: { x: 580, y: 180 },
        config: {
          provider: 'openai-compatible',
          model: 'deepseek-flash',
          promptTemplate:
            '请只依据以下文档片段回答，并注明出处。\n文档：{{ nodes.retrieve.output.text }}\n问题：{{ vars.question }}',
          maxTokens: 1024,
        },
      },
      {
        key: 'end',
        type: 'end',
        name: '收口',
        position: { x: 840, y: 180 },
        config: {
          resultMapping: {
            answer: '{{ nodes.answer.output.text }}',
            sources: '{{ nodes.retrieve.output.citations }}',
          },
        },
      },
    ],
    edges: [
      { from: 'start', to: 'retrieve' },
      { from: 'retrieve', to: 'answer' },
      { from: 'answer', to: 'end' },
    ],
  }
  try {
    const created = await workflowApi.create(`文档问答 · ${selected.value.name}`, '文档检索 + 模型作答')
    await workflowApi.saveDraft(created.id, dsl)
    notice.value = '工作流已创建：检索命中真实文档，回答走服务端配置的模型，可在画布上切换模型连接'
    await router.push({ name: 'editor', params: { id: created.id } })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

onMounted(loadBases)
</script>

<template>
  <div class="af-page">
    <div class="af-page-title">
      知识库
    </div>
    <p
      class="af-faint"
      style="font-size: 12px; line-height: 1.7; margin: 4px 0 14px; max-width: 820px"
    >
      导入自己的文档后，画布里的「检索」节点会从这些文档里做真实 BM25 检索，
      返回带出处的原文片段，不再返回固定示例语料。文档按空间隔离，纯文本保存在服务端。
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

    <div class="kb-layout">
      <section class="af-panel kb-side">
        <div
          class="af-label"
          style="margin-top: 0"
        >
          知识库（{{ bases.length }}）
        </div>
        <div
          v-if="!bases.length"
          class="af-faint"
          style="font-size: 12px; line-height: 1.7"
        >
          还没有知识库。新建一个，然后粘贴文本或上传 .txt / .md 文件。
        </div>
        <button
          v-for="b in bases"
          :key="b.id"
          class="kb-base"
          :class="{ 'kb-base--active': b.id === selectedId }"
          @click="selectedId = b.id; loadDocuments()"
        >
          <span
            class="af-dim"
            style="font-size: 12px"
          >{{ b.name }}</span>
          <span
            class="af-faint"
            style="font-size: 11px"
          >{{ b.documentCount }} 文档 · {{ b.chunkCount }} 片段</span>
        </button>

        <div style="border-top: 1px solid var(--af-border); margin: 12px 0; padding-top: 12px">
          <label class="af-label">新建知识库</label>
          <input
            v-model="newName"
            class="af-input"
            placeholder="例如：产品售后手册"
          >
          <input
            v-model="newDesc"
            class="af-input"
            placeholder="描述（可选）"
            style="margin-top: 6px"
          >
          <button
            class="af-btn af-btn--sm af-btn--primary"
            style="margin-top: 8px"
            :disabled="busy || !newName.trim()"
            @click="createBase"
          >
            创建
          </button>
        </div>
      </section>

      <section
        v-if="selected"
        class="af-panel kb-main"
      >
        <div style="display: flex; align-items: center; gap: 10px">
          <div
            class="af-page-title"
            style="margin: 0"
          >
            {{ selected.name }}
          </div>
          <div class="af-spacer" />
          <button
            class="af-btn af-btn--sm"
            :disabled="busy"
            @click="createWorkflow"
          >
            用此知识库创建问答工作流
          </button>
          <button
            class="af-btn af-btn--sm af-btn--danger"
            :disabled="busy"
            @click="removeBase(selected.id)"
          >
            删除
          </button>
        </div>

        <div class="kb-block">
          <div class="af-label">
            导入文档
          </div>
          <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
            <input
              v-model="docTitle"
              class="af-input"
              placeholder="文档标题（可选，默认取文件名）"
            >
            <label
              class="af-btn af-btn--sm"
              style="cursor: pointer; white-space: nowrap"
            >
              选择 .txt / .md
              <input
                type="file"
                accept=".txt,.md,.markdown,.csv,text/plain"
                style="display: none"
                @change="onFile"
              >
            </label>
          </div>
          <textarea
            v-model="docContent"
            class="af-textarea"
            rows="7"
            placeholder="在这里粘贴文档正文，或先点右侧选择文件；导入时会按段落自动切片"
          />
          <div style="display: flex; align-items: center; gap: 10px; margin-top: 8px">
            <span
              class="af-faint"
              style="font-size: 11px"
            >当前 {{ docContent.length }} 字符 · 上限 200000</span>
            <div class="af-spacer" />
            <button
              class="af-btn af-btn--sm af-btn--primary"
              :disabled="busy || !docContent.trim()"
              @click="addDocument"
            >
              导入文档
            </button>
          </div>
        </div>

        <div class="kb-block">
          <div class="af-label">
            已导入文档（{{ documents.length }}）
          </div>
          <table
            v-if="documents.length"
            class="af-table"
          >
            <thead>
              <tr><th>标题</th><th>字符</th><th>片段</th><th>来源</th><th /></tr>
            </thead>
            <tbody>
              <tr
                v-for="d in documents"
                :key="d.id"
              >
                <td>{{ d.title }}</td>
                <td class="mono">
                  {{ d.charCount }}
                </td>
                <td class="mono">
                  {{ d.chunkCount }}
                </td>
                <td class="af-faint">
                  {{ d.source }}
                </td>
                <td>
                  <button
                    class="af-btn af-btn--sm af-btn--danger"
                    :disabled="busy"
                    @click="removeDocument(d.id)"
                  >
                    删除
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <p
            v-else
            class="af-faint"
            style="font-size: 12px"
          >
            还没有文档。先导入一份再试检索。
          </p>
        </div>

        <div class="kb-block">
          <div class="af-label">
            检索自测
          </div>
          <div style="display: flex; gap: 8px">
            <input
              v-model="query"
              class="af-input"
              placeholder="输入问题，看看能召回哪些原文片段"
              @keydown.enter="testSearch"
            >
            <button
              class="af-btn af-btn--sm af-btn--primary"
              style="white-space: nowrap"
              :disabled="busy || !query.trim()"
              @click="testSearch"
            >
              检索
            </button>
          </div>
          <p
            v-if="searchMeta"
            class="af-faint"
            style="font-size: 11px; margin: 8px 0"
          >
            扫描 {{ searchMeta.scanned }} 个片段，命中 {{ searchMeta.matched }} 个，按相关度排序。
          </p>
          <div
            v-for="(h, i) in hits"
            :key="h.chunkId"
            class="kb-hit"
          >
            <div
              class="af-dim"
              style="font-size: 11px"
            >
              [{{ i + 1 }}] {{ h.documentTitle }} · 片段 {{ h.ordinal + 1 }} · 相关度 {{ h.score.toFixed(4) }}
            </div>
            <div style="white-space: pre-wrap; line-height: 1.6; margin-top: 4px">
              {{ h.content }}
            </div>
          </div>
        </div>
      </section>

      <section
        v-else
        class="af-panel kb-main af-faint"
        style="font-size: 12px"
      >
        左侧选一个知识库，即可导入文档、试检索，并一键生成引用它的工作流。
      </section>
    </div>
  </div>
</template>

<style scoped>
.kb-layout {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.kb-side {
  width: 260px;
  flex: none;
  padding: 12px;
}

.kb-main {
  flex: 1;
  min-width: 0;
  padding: 14px;
}

.kb-base {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  margin-bottom: 6px;
  background: var(--af-surface);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
  cursor: pointer;
}

.kb-base--active {
  border-color: var(--af-accent-border);
}

.kb-block {
  border-top: 1px solid var(--af-border);
  margin-top: 14px;
  padding-top: 12px;
}

.kb-hit {
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
  background: var(--af-surface);
  padding: 10px;
  margin-top: 8px;
  font-size: 12px;
}
</style>
