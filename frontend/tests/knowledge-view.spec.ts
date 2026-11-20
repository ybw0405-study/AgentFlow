import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import KnowledgeView from '@/views/KnowledgeView.vue'

const mocks = vi.hoisted(() => ({
  bases: vi.fn(),
  createBase: vi.fn(),
  removeBase: vi.fn(),
  documents: vi.fn(),
  addDocument: vi.fn(),
  removeDocument: vi.fn(),
  search: vi.fn(),
  createWorkflow: vi.fn(),
  saveDraft: vi.fn(),
  push: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    knowledgeApi: {
      bases: mocks.bases,
      createBase: mocks.createBase,
      removeBase: mocks.removeBase,
      documents: mocks.documents,
      addDocument: mocks.addDocument,
      removeDocument: mocks.removeDocument,
      search: mocks.search,
    },
    workflowApi: {
      create: mocks.createWorkflow,
      saveDraft: mocks.saveDraft,
    },
  }
})

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))

const baseRows = [
  { id: 'kb-1', name: '售后手册', description: null, documentCount: 1, chunkCount: 3,
    createdAt: null },
  { id: 'kb-2', name: '价格表', description: null, documentCount: 0, chunkCount: 0,
    createdAt: null },
]

async function mountView() {
  const wrapper = mount(KnowledgeView)
  await flushPromises()
  return wrapper
}

function button(wrapper: Awaited<ReturnType<typeof mountView>>, label: string) {
  const all = wrapper.findAll('button')
  const exact = all.find((b) => b.text().trim() === label)
  const found = exact ?? all.find((b) => b.text().includes(label))
  if (!found) throw new Error(`button not found: ${label}`)
  return found
}

function input(wrapper: Awaited<ReturnType<typeof mountView>>, placeholder: string) {
  const found = wrapper.findAll('input').find(
    (i) => (i.attributes('placeholder') ?? '').startsWith(placeholder),
  )
  if (!found) throw new Error(`input not found: ${placeholder}`)
  return found
}

describe('知识库页面', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((m) => m.mockReset())
    mocks.bases.mockResolvedValue(baseRows)
    mocks.documents.mockResolvedValue([
      { id: 'doc-1', kbId: 'kb-1', title: '退款政策', source: 'TEXT',
        charCount: 120, chunkCount: 1, createdAt: null },
    ])
    mocks.createBase.mockResolvedValue({ ...baseRows[1], id: 'kb-3', name: '新建库' })
    mocks.addDocument.mockResolvedValue({
      id: 'doc-2', kbId: 'kb-1', title: '新文档', source: 'TEXT',
      charCount: 10, chunkCount: 1, createdAt: null,
    })
    mocks.search.mockResolvedValue({
      results: [{
        chunkId: 'c-1', documentId: 'doc-1', documentTitle: '退款政策', kbId: 'kb-1',
        ordinal: 0, content: '七天内可全额退款。', score: 0.8123,
      }],
      query: '退款', matchedChunks: 1, scannedChunks: 3,
    })
    mocks.createWorkflow.mockResolvedValue({ id: 'wf-new' })
    mocks.saveDraft.mockResolvedValue({ ok: true, checksum: 'x' })
    mocks.push.mockResolvedValue(undefined)
  })

  it('列出本空间的知识库与已导入文档', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('售后手册')
    expect(wrapper.text()).toContain('价格表')
    expect(wrapper.text()).toContain('退款政策')
    expect(mocks.documents).toHaveBeenCalledWith('kb-1')
  })

  it('新建知识库会调用创建接口并刷新列表', async () => {
    const wrapper = await mountView()
    await input(wrapper, '例如：产品售后手册').setValue('新建库')
    await button(wrapper, '创建').trigger('click')
    await flushPromises()
    expect(mocks.createBase).toHaveBeenCalledWith('新建库', undefined)
    expect(mocks.bases).toHaveBeenCalledTimes(2)
  })

  it('粘贴正文后导入文档会写入服务端', async () => {
    const wrapper = await mountView()
    await wrapper.find('textarea').setValue('退款需在订单页提交申请。')
    await button(wrapper, '导入文档').trigger('click')
    await flushPromises()
    expect(mocks.addDocument).toHaveBeenCalledWith('kb-1', expect.objectContaining({
      content: '退款需在订单页提交申请。',
      source: 'TEXT',
    }))
    expect(wrapper.text()).toContain('切成 1 个片段')
  })

  it('检索自测展示真实原文片段与相关度', async () => {
    const wrapper = await mountView()
    await input(wrapper, '输入问题').setValue('退款')
    await button(wrapper, '检索').trigger('click')
    await flushPromises()
    expect(mocks.search).toHaveBeenCalledWith({
      knowledgeBaseIds: ['kb-1'], query: '退款', topK: 5,
    })
    expect(wrapper.text()).toContain('七天内可全额退款。')
    expect(wrapper.text()).toContain('0.8123')
    expect(wrapper.text()).toContain('扫描 3 个片段')
  })

  it('一键创建的问答工作流引用真实知识库 id，并走真实模型端点', async () => {
    const wrapper = await mountView()
    await button(wrapper, '用此知识库创建问答工作流').trigger('click')
    await flushPromises()
    expect(mocks.createWorkflow).toHaveBeenCalledTimes(1)
    const [, dsl] = mocks.saveDraft.mock.calls[0]
    const retrieval = dsl.nodes.find((n: { type: string }) => n.type === 'retrieval')
    expect(retrieval.config.knowledgeBaseIds).toEqual(['kb-1'])
    const answer = dsl.nodes.find((n: { type: string }) => n.type === 'llm')
    expect(answer.config.provider).toBe('openai-compatible')
    expect(mocks.push).toHaveBeenCalledWith({ name: 'editor', params: { id: 'wf-new' } })
    expect(wrapper.text()).toContain('回答走服务端配置的模型')
  })
})
