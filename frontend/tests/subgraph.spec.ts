import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type * as ApiModule from '@/api'
import {
  duplicateSubgraphKeys,
  isSubgraphPath,
  readSubgraph,
  readSubgraphPath,
  subgraphRefs,
  subgraphRemovalError,
  subgraphTitle,
  writeSubgraph,
  writeSubgraphPath,
} from '@/dsl/subgraph'
import { validateDslLocal } from '@/dsl/validate'
import type { DslNode, WorkflowDsl } from '@/dsl/types'
import ConfigDrawer from '@/components/canvas/ConfigDrawer.vue'
import CanvasEditorView from '@/views/CanvasEditorView.vue'

// ---------- 夹具 ----------

function loopBody(): { nodes: DslNode[]; edges: WorkflowDsl['edges'] } {
  return {
    nodes: [
      {
        key: 'body_llm',
        type: 'llm',
        config: { model: 'deepseek-chat', promptTemplate: '{{ vars.item }}' },
      },
      {
        key: 'body_end',
        type: 'end',
        config: { resultMapping: { v: '{{ nodes.body_llm.output.text }}' } },
      },
    ],
    edges: [{ from: 'body_llm', to: 'body_end' }],
  }
}

function dslWithLoop(): WorkflowDsl {
  return {
    dslVersion: '1.0',
    variables: { items: { type: 'array' }, question: { type: 'string' } },
    nodes: [
      { key: 'start', type: 'start', config: {} },
      {
        key: 'loop_1',
        type: 'loop',
        config: {
          itemsExpression: 'vars.items',
          itemVar: 'item',
          indexVar: 'index',
          body: loopBody(),
        },
      },
      { key: 'end', type: 'end', config: { resultMapping: { ok: '{{ nodes.loop_1.output }}' } } },
    ],
    edges: [
      { from: 'start', to: 'loop_1' },
      { from: 'loop_1', to: 'end' },
    ],
  }
}

function dslWithParallel(): WorkflowDsl {
  const end = (key: string): DslNode => ({
    key,
    type: 'end',
    config: { resultMapping: { value: key } },
  })
  return {
    dslVersion: '1.0',
    variables: {},
    nodes: [
      { key: 'start', type: 'start', config: {} },
      {
        key: 'fan',
        type: 'parallel',
        config: {
          join: 'all',
          maxConcurrency: 2,
          branches: {
            // 每个分支至少 2 个节点：引擎运行期按 WorkflowDsl 解析子图
            a: {
              nodes: [
                {
                  key: 'a_prep',
                  type: 'transform',
                  config: { mappings: [{ target: 'vars.a', template: 'a' }] },
                },
                end('a_end'),
              ],
              edges: [{ from: 'a_prep', to: 'a_end' }],
            },
            b: {
              nodes: [
                {
                  key: 'b_prep',
                  type: 'transform',
                  config: { mappings: [{ target: 'vars.b', template: 'b' }] },
                },
                end('b_end'),
              ],
              edges: [{ from: 'b_prep', to: 'b_end' }],
            },
          },
        },
      },
      { key: 'end', type: 'end', config: { resultMapping: { ok: 'done' } } },
    ],
    edges: [
      { from: 'start', to: 'fan' },
      { from: 'fan', to: 'end' },
    ],
  }
}

// ---------- 纯函数 ----------

describe('子图读写', () => {
  it('读取 loop.body 并给出可读标题', () => {
    const dsl = dslWithLoop()
    const graph = readSubgraph(dsl, { nodeKey: 'loop_1' })
    expect(graph).not.toBeNull()
    expect(graph?.nodes.map((n) => n.key)).toEqual(['body_llm', 'body_end'])
    expect(graph?.edges).toEqual([{ from: 'body_llm', to: 'body_end' }])
    expect(subgraphTitle(dsl, { nodeKey: 'loop_1' })).toBe('loop_1.body')
  })

  it('写回 loop.body 返回新对象，入参不动', () => {
    const dsl = dslWithLoop()
    const before = JSON.stringify(dsl)
    const graph = readSubgraph(dsl, { nodeKey: 'loop_1' })
    expect(graph).not.toBeNull()
    const next = writeSubgraph(dsl, { nodeKey: 'loop_1' }, {
      nodes: [
        ...(graph?.nodes ?? []),
        { key: 'body_tool', type: 'tool', config: { connectorId: 'mock.search', operation: 'search' } },
      ],
      edges: graph?.edges ?? [],
    })
    expect(next).not.toBe(dsl)
    expect(JSON.stringify(dsl)).toBe(before)
    expect(readSubgraph(next, { nodeKey: 'loop_1' })?.nodes.map((n) => n.key)).toEqual([
      'body_llm',
      'body_end',
      'body_tool',
    ])
    // 顶层其它节点原样保留
    expect(next.nodes.map((n) => n.key)).toEqual(['start', 'loop_1', 'end'])
  })

  it('读写往返后内容一致', () => {
    const dsl = dslWithLoop()
    const graph = readSubgraph(dsl, { nodeKey: 'loop_1' })
    if (!graph) throw new Error('fixture 应有 body 子图')
    const next = writeSubgraph(dsl, { nodeKey: 'loop_1' }, graph)
    expect(readSubgraph(next, { nodeKey: 'loop_1' })).toEqual(graph)
  })

  it('错误的 ref 返回 null，写回时原样返回同一个对象', () => {
    const dsl = dslWithLoop()
    expect(readSubgraph(dsl, { nodeKey: 'ghost' })).toBeNull()
    // loop 没有分支概念
    expect(readSubgraph(dsl, { nodeKey: 'loop_1', branch: 'a' })).toBeNull()
    // 类型不支持
    expect(readSubgraph(dsl, { nodeKey: 'start' })).toBeNull()
    expect(writeSubgraph(dsl, { nodeKey: 'ghost' }, { nodes: [], edges: [] })).toBe(dsl)
    expect(writeSubgraph(dsl, { nodeKey: 'loop_1', branch: 'a' }, { nodes: [], edges: [] })).toBe(dsl)
  })

  it('parallel 按分支定位，写一个分支不影响另一个', () => {
    const dsl = dslWithParallel()
    const a = readSubgraph(dsl, { nodeKey: 'fan', branch: 'a' })
    expect(a?.nodes.map((n) => n.key)).toEqual(['a_prep', 'a_end'])
    expect(subgraphTitle(dsl, { nodeKey: 'fan', branch: 'b' })).toBe('fan.b')
    expect(readSubgraph(dsl, { nodeKey: 'fan' })).toBeNull()
    expect(readSubgraph(dsl, { nodeKey: 'fan', branch: 'zzz' })).toBeNull()

    const next = writeSubgraph(dsl, { nodeKey: 'fan', branch: 'b' }, {
      nodes: [{ key: 'b_end', type: 'end', config: { resultMapping: { v: 'changed' } } }],
      edges: [],
    })
    expect(readSubgraph(next, { nodeKey: 'fan', branch: 'a' })).toEqual(a)
    expect(readSubgraph(next, { nodeKey: 'fan', branch: 'b' })?.nodes[0].config).toEqual({
      resultMapping: { v: 'changed' },
    })
    // 不存在的分支写回不生效
    expect(writeSubgraph(dsl, { nodeKey: 'fan', branch: 'zzz' }, { nodes: [], edges: [] })).toBe(dsl)
  })

  it('subgraphRefs 列出当前层所有可进入的子图', () => {
    expect(subgraphRefs(dslWithLoop())).toEqual([{ nodeKey: 'loop_1' }])
    expect(subgraphRefs(dslWithParallel())).toEqual([
      { nodeKey: 'fan', branch: 'a' },
      { nodeKey: 'fan', branch: 'b' },
    ])
  })

  it('isSubgraphPath 只接受成形且非空的路径', () => {
    expect(isSubgraphPath([])).toBe(false)
    expect(isSubgraphPath([{ nodeKey: '' }])).toBe(false)
    expect(isSubgraphPath([{ nodeKey: 'loop_1' }])).toBe(true)
  })

  it('嵌套路径逐层读写', () => {
    const inner = dslWithLoop()
    const outer: WorkflowDsl = {
      ...dslWithParallel(),
      nodes: [
        { key: 'start', type: 'start', config: {} },
        {
          key: 'loop_2',
          type: 'loop',
          config: { itemsExpression: '[]', body: { nodes: inner.nodes, edges: inner.edges } },
        },
        { key: 'end', type: 'end', config: { resultMapping: { ok: 'done' } } },
      ],
      edges: [
        { from: 'start', to: 'loop_2' },
        { from: 'loop_2', to: 'end' },
      ],
    }
    const path = [{ nodeKey: 'loop_2' }, { nodeKey: 'loop_1' }]
    const graph = readSubgraphPath(outer, path)
    expect(graph?.nodes.map((n) => n.key)).toEqual(['body_llm', 'body_end'])

    const next = writeSubgraphPath(outer, path, {
      nodes: [...(graph?.nodes ?? []), { key: 'deep_end', type: 'end', config: { resultMapping: { v: 'x' } } }],
      edges: graph?.edges ?? [],
    })
    expect(readSubgraphPath(next, path)?.nodes.map((n) => n.key)).toEqual([
      'body_llm',
      'body_end',
      'deep_end',
    ])
    // 外层未受影响
    expect(next.nodes.map((n) => n.key)).toEqual(['start', 'loop_2', 'end'])
  })

  it('子图内允许和外层同名的 key（只在层内唯一）', () => {
    const dsl = dslWithLoop()
    const body = readSubgraph(dsl, { nodeKey: 'loop_1' })
    if (!body) throw new Error('fixture 应有 body 子图')
    const withClash: WorkflowDsl = writeSubgraph(dsl, { nodeKey: 'loop_1' }, {
      nodes: [...body.nodes, { key: 'end', type: 'end', config: { resultMapping: { v: 'shadow' } } }],
      edges: body.edges,
    })
    const inner = readSubgraph(withClash, { nodeKey: 'loop_1' })
    if (!inner) throw new Error('写回后应仍有 body 子图')
    expect(duplicateSubgraphKeys(inner)).toEqual([])
    // 与外层同名的 end 不应被当成重复 key 报错
    expect(validateDslLocal(withClash).some((i) => i.message.includes('重复 nodeKey'))).toBe(false)
  })
})

// ---------- 校验定位 ----------

describe('子图校验', () => {
  it('合法子图不产生问题', () => {
    expect(validateDslLocal(dslWithLoop())).toEqual([])
    expect(validateDslLocal(dslWithParallel())).toEqual([])
  })

  it('子图内重复 key 定位到 子图路径#key', () => {
    const dsl = dslWithLoop()
    const body = readSubgraph(dsl, { nodeKey: 'loop_1' })
    if (!body) throw new Error('fixture 应有 body 子图')
    const broken = writeSubgraph(dsl, { nodeKey: 'loop_1' }, {
      nodes: [...body.nodes, { key: 'body_end', type: 'end', config: { resultMapping: { v: 'dup' } } }],
      edges: body.edges,
    })
    const issues = validateDslLocal(broken)
    expect(issues.some((i) => i.nodeKey === 'loop_1.body#body_end' && i.message.includes('重复'))).toBe(true)
  })

  it('子图没有 end 不算问题（出口由上层节点决定）', () => {
    const dsl = dslWithLoop()
    const body = readSubgraph(dsl, { nodeKey: 'loop_1' })
    const withoutEnd = writeSubgraph(dsl, { nodeKey: 'loop_1' }, {
      nodes: (body?.nodes ?? []).filter((n) => n.type !== 'end'),
      edges: [],
    })
    const issues = validateDslLocal(withoutEnd)
    // 引擎只要求整图有 end；子图里强求 end 会让合法图报假错（曾出现过，见 0.3.2）
    expect(issues.filter((i) => i.message.includes('end'))).toHaveLength(0)
  })

  it('整图缺 end 仍然报错', () => {
    const dsl = dslWithLoop()
    const broken = { ...dsl, nodes: dsl.nodes.filter((n) => n.type !== 'end') }
    const issues = validateDslLocal(broken)
    expect(issues.some((i) => i.message.includes('至少需要 1 个 end'))).toBe(true)
  })

  it('子图内 start 被拒绝，分支边规则同样生效', () => {
    const dsl = dslWithLoop()
    const body = readSubgraph(dsl, { nodeKey: 'loop_1' })
    if (!body) throw new Error('fixture 应有 body 子图')
    const broken = writeSubgraph(dsl, { nodeKey: 'loop_1' }, {
      nodes: [
        { key: 'body_start', type: 'start', config: {} },
        { key: 'body_llm', type: 'llm', config: { model: 'm', promptTemplate: '{{ vars.item }}' } },
        { key: 'body_end', type: 'end', config: { resultMapping: { v: 'x' } } },
      ],
      edges: [
        { from: 'body_start', to: 'body_llm' },
        { from: 'body_llm', to: 'body_end', branch: 'yes' },
      ],
    })
    const issues = validateDslLocal(broken)
    expect(issues.some((i) => i.nodeKey === 'loop_1.body#body_start')).toBe(true)
    expect(
      issues.some(
        (i) => i.nodeKey === 'loop_1.body#body_llm' && i.message.includes('只有 condition / human_gate'),
      ),
    ).toBe(true)
  })

  it('parallel 分支的问题定位到 节点.分支#key', () => {
    const dsl = dslWithParallel()
    const branchA = readSubgraph(dsl, { nodeKey: 'fan', branch: 'a' })
    if (!branchA) throw new Error('fixture 应有分支 a')
    const broken = writeSubgraph(dsl, { nodeKey: 'fan', branch: 'a' }, {
      nodes: [{ key: 'a_end', type: 'end', config: {} }],
      edges: branchA.edges,
    })
    const issues = validateDslLocal(broken)
    expect(issues.some((i) => i.nodeKey === 'fan.a#a_end' && i.message.includes('resultMapping'))).toBe(
      true,
    )
  })

  it('子图内引用外层节点与迭代变量不误报', () => {
    const dsl = dslWithLoop()
    const issues = validateDslLocal(dsl)
    expect(issues.filter((i) => i.message.includes('vars.item'))).toHaveLength(0)
    expect(issues.filter((i) => i.message.includes('nodes.loop_1'))).toHaveLength(0)
  })
})

// ---------- 删除保护 ----------

describe('删除保护', () => {
  it('子图里的 end 可以删（不设末位保护）', () => {
    const graph = readSubgraph(dslWithLoop(), { nodeKey: 'loop_1' })
    if (!graph) throw new Error('fixture 应有 body 子图')
    expect(subgraphRemovalError(graph, 'body_end')).toBeNull()
    expect(subgraphRemovalError(graph, 'body_llm')).toBeNull()
  })

  it('整图最后一个 end 不能删', () => {
    const dsl = dslWithLoop()
    const graph = { nodes: dsl.nodes, edges: [] }
    expect(subgraphRemovalError(graph, 'end', true)).toContain('至少要保留一个 end')
    expect(subgraphRemovalError(graph, 'loop_1', true)).toBeNull()
  })

  it('还有其它 end 时可以删', () => {
    const graph = readSubgraph(dslWithLoop(), { nodeKey: 'loop_1' })
    if (!graph) throw new Error('fixture 应有 body 子图')
    const withTwo = {
      nodes: [...graph.nodes, { key: 'body_end_2', type: 'end' as const, config: { resultMapping: { v: 'y' } } }],
      edges: graph.edges,
    }
    expect(subgraphRemovalError(withTwo, 'body_end')).toBeNull()
  })
})

// ---------- 配置抽屉 ----------

describe('配置抽屉的子图入口', () => {
  const catalog = { loaded: true, configured: true, error: null, models: [] }

  it('loop 提供「在画布中编辑」按钮并保留高级 JSON', () => {
    const dsl = dslWithLoop()
    const node = dsl.nodes[1]
    const wrapper = mount(ConfigDrawer, {
      props: { node, issues: [], modelCatalog: catalog, llmConnections: [] },
    })
    const btn = wrapper.findAll('button').find((b) => b.text() === '在画布中编辑')
    expect(btn).toBeTruthy()
    expect(wrapper.text()).toContain('高级：直接编辑 body JSON')
  })

  it('点击按钮发出 loop 的 ref', async () => {
    const dsl = dslWithLoop()
    const wrapper = mount(ConfigDrawer, {
      props: { node: dsl.nodes[1], issues: [], modelCatalog: catalog, llmConnections: [] },
    })
    const btn = wrapper.findAll('button').find((b) => b.text() === '在画布中编辑')
    await btn!.trigger('click')
    expect(wrapper.emitted('edit-subgraph')).toEqual([[{ nodeKey: 'loop_1' }]])
  })

  it('parallel 每个分支各有一个入口', async () => {
    const dsl = dslWithParallel()
    const wrapper = mount(ConfigDrawer, {
      props: { node: dsl.nodes[1], issues: [], modelCatalog: catalog, llmConnections: [] },
    })
    const rows = wrapper.findAll('.af-subgraph-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('a')
    expect(rows[1].text()).toContain('b')
    await rows[1].find('button').trigger('click')
    expect(wrapper.emitted('edit-subgraph')).toEqual([[{ nodeKey: 'fan', branch: 'b' }]])
  })

  it('子画布改完回来，JSON 文本域是最新内容', async () => {
    const dsl = dslWithLoop()
    const wrapper = mount(ConfigDrawer, {
      props: { node: dsl.nodes[1], issues: [], modelCatalog: catalog, llmConnections: [] },
    })
    const body = readSubgraph(dsl, { nodeKey: 'loop_1' })
    if (!body) throw new Error('fixture 应有 body 子图')
    const updated = writeSubgraph(dsl, { nodeKey: 'loop_1' }, {
      nodes: [...body.nodes, { key: 'added', type: 'tool', config: { connectorId: 'x', operation: 'y' } }],
      edges: body.edges,
    })
    await wrapper.setProps({ node: updated.nodes[1], issues: [], modelCatalog: catalog, llmConnections: [] })
    const textarea = wrapper.findAll('textarea').at(-1)
    expect(textarea?.element.value).toContain('"added"')
  })

  it('JSON 解析失败时不写回配置', async () => {
    const dsl = dslWithLoop()
    const wrapper = mount(ConfigDrawer, {
      props: { node: dsl.nodes[1], issues: [], modelCatalog: catalog, llmConnections: [] },
    })
    const textarea = wrapper.findAll('textarea').at(-1)
    await textarea!.setValue('{ broken')
    expect(wrapper.emitted('update')).toBeUndefined()
    expect(wrapper.text()).toContain('JSON 未生效')
  })
})

// ---------- 画布内进出子图 ----------

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  saveDraft: vi.fn(),
  push: vi.fn(),
  models: vi.fn(),
  connections: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    workflowApi: { ...actual.workflowApi, detail: mocks.detail, saveDraft: mocks.saveDraft },
    executionApi: { ...actual.executionApi, trigger: vi.fn() },
    llmApi: { ...actual.llmApi, models: mocks.models },
    connectorApi: { ...actual.connectorApi, list: mocks.connections },
  }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'wf-1' } }),
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    name: 'VueFlow',
    props: ['nodes', 'edges', 'deleteKeyCode'],
    emits: ['connect', 'nodeClick', 'edgeClick', 'paneClick', 'update:nodes', 'update:edges'],
    template: '<div class="flow-stub"><slot /></div>',
  },
  Handle: { name: 'Handle', template: '<span />' },
  Position: { Left: 'left', Right: 'right' },
}))

vi.mock('@vue-flow/background', () => ({
  Background: { name: 'Background', template: '<div />' },
}))

vi.mock('@vue-flow/controls', () => ({
  Controls: { name: 'Controls', template: '<div />' },
}))

async function mountCanvas(dsl: WorkflowDsl) {
  mocks.detail.mockResolvedValue({
    definition: {
      seq: 1,
      id: 'wf-1',
      name: 'demo',
      description: null,
      status: 'DRAFT',
      currentVersionId: null,
      draftDsl: JSON.stringify(dsl),
      draftChecksum: null,
      createdBy: 'u-editor',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
    versions: [],
  })
  const wrapper = mount(CanvasEditorView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  await flushPromises()
  return wrapper
}

describe('画布内进出子图', () => {
  beforeEach(() => {
    mocks.detail.mockReset()
    mocks.saveDraft.mockReset()
    mocks.saveDraft.mockResolvedValue(undefined)
    mocks.connections.mockReset()
    mocks.connections.mockResolvedValue([])
    mocks.models.mockReset()
    mocks.models.mockResolvedValue({ configured: false, defaultModel: '', models: [], error: null })
  })

  function flow(wrapper: Awaited<ReturnType<typeof mountCanvas>>) {
    return wrapper.findComponent({ name: 'VueFlow' })
  }

  async function enterLoopBody(
    wrapper: Awaited<ReturnType<typeof mountCanvas>>,
    root: WorkflowDsl,
  ) {
    flow(wrapper).vm.$emit('nodeClick', { node: { id: 'loop_1' } })
    await flushPromises()
    const btn = wrapper.findAll('.af-drawer button').find((b) => b.text() === '在画布中编辑')
    expect(btn, `夹具应能进入 ${root.nodes[1].key} 的子图`).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
  }

  it('进入 loop.body 后画布渲染子图节点，面包屑可回上层', async () => {
    const dsl = dslWithLoop()
    const wrapper = await mountCanvas(dsl)

    await enterLoopBody(wrapper, dsl)
    expect((flow(wrapper).props('nodes') as { id: string }[]).map((n) => n.id)).toEqual([
      'body_llm',
      'body_end',
    ])
    const crumb = wrapper.find('.af-breadcrumb')
    expect(crumb.text()).toContain('工作流')
    expect(crumb.text()).toContain('loop_1')
    expect(crumb.text()).toContain('body')

    await wrapper.findAll('.af-breadcrumb button').find((b) => b.text() === '工作流')!.trigger('click')
    await flushPromises()
    expect((flow(wrapper).props('nodes') as { id: string }[]).map((n) => n.id)).toEqual([
      'start',
      'loop_1',
      'end',
    ])
  })

  it('子图内删 end 直接生效，整图删最后一个 end 被拒绝并给出提示', async () => {
    const dsl = dslWithLoop()
    const wrapper = await mountCanvas(dsl)
    await enterLoopBody(wrapper, dsl)

    flow(wrapper).vm.$emit('nodeClick', { node: { id: 'body_end' } })
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    await flushPromises()
    expect((flow(wrapper).props('nodes') as { id: string }[]).map((n) => n.id)).toEqual(['body_llm'])

    // 回到整图：最后一个 end 是收口，删掉会被拒绝
    await wrapper.findAll('.af-breadcrumb button').find((b) => b.text() === '工作流')!.trigger('click')
    await flushPromises()
    flow(wrapper).vm.$emit('nodeClick', { node: { id: 'end' } })
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    await flushPromises()
    expect((flow(wrapper).props('nodes') as { id: string }[]).map((n) => n.id)).toContain('end')
    expect(wrapper.text()).toContain('至少要保留一个 end')
  })

  it('子图内不能新建 start，新增节点会写回 DSL', async () => {
    const dsl = dslWithLoop()
    const wrapper = await mountCanvas(dsl)
    await enterLoopBody(wrapper, dsl)

    const startBtn = wrapper
      .find('.af-palette')
      .findAll('button')
      .find((b) => b.find('.af-palette__type').text() === 'start')
    expect(startBtn?.attributes('disabled')).toBeDefined()

    const llmBtn = wrapper
      .find('.af-palette')
      .findAll('button')
      .find((b) => b.find('.af-palette__type').text() === 'llm')
    await llmBtn!.trigger('click')
    await flushPromises()
    const afterAdd = (flow(wrapper).props('nodes') as { id: string }[]).map((n) => n.id)
    expect(afterAdd).toHaveLength(3)

    // 退出再进入：新增节点仍在，说明确实写回了 DSL 而不是只改了内存画布
    await wrapper.findAll('.af-breadcrumb button').find((b) => b.text() === '工作流')!.trigger('click')
    await flushPromises()
    await enterLoopBody(wrapper, dsl)
    expect((flow(wrapper).props('nodes') as { id: string }[]).map((n) => n.id)).toEqual(afterAdd)
  })
})
