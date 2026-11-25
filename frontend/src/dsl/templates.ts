import type { WorkflowDsl } from './types'

/** 常用流程骨架。模板只作用于草稿，已发布版本不受影响；
 * 每个模板都在 templates.spec.ts 里过一遍本地校验，保证拖进来就能发布。 */
export interface WorkflowTemplate {
  id: string
  name: string
  description: string
  dsl: WorkflowDsl
}

const question = { question: { type: 'string' as const, required: true } }

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  {
    id: 'qa',
    name: '单模型问答',
    description: '入口 → 模型回答 → 收口，最小可跑流程',
    dsl: {
      dslVersion: '1.0',
      variables: question,
      nodes: [
        { key: 'start', type: 'start', name: '入口', position: { x: 60, y: 160 }, config: {} },
        {
          key: 'answer',
          type: 'llm',
          name: '生成回答',
          position: { x: 340, y: 160 },
          config: {
            provider: 'openai-compatible',
            model: 'deepseek-flash',
            promptTemplate: '{{ vars.question }}',
            maxTokens: 1024,
          },
        },
        {
          key: 'end',
          type: 'end',
          name: '收口',
          position: { x: 620, y: 160 },
          config: { resultMapping: { answer: '{{ nodes.answer.output.text }}' } },
        },
      ],
      edges: [{ from: 'start', to: 'answer' }, { from: 'answer', to: 'end' }],
    },
  },
  {
    id: 'gate',
    name: '人工审批后回复',
    description: '起草 → 审批 → 通过/驳回双出口 → 收口',
    dsl: {
      dslVersion: '1.0',
      variables: { ...question, outcome: { type: 'string' as const } },
      nodes: [
        { key: 'start', type: 'start', name: '入口', position: { x: 60, y: 200 }, config: {} },
        {
          key: 'draft',
          type: 'llm',
          name: '起草',
          position: { x: 300, y: 200 },
          config: {
            provider: 'openai-compatible',
            model: 'deepseek-flash',
            promptTemplate: '请就以下问题起草回复：{{ vars.question }}',
            maxTokens: 1024,
          },
        },
        {
          key: 'review',
          type: 'human_gate',
          name: '人工审批',
          position: { x: 560, y: 200 },
          config: {
            approvers: { type: 'ROLE', role: 'ADMIN' },
            promptTemplate: '请确认草稿是否可用：{{ vars.question }}',
            timeoutMs: 86_400_000,
            onTimeout: { action: 'REJECT' },
          },
        },
        {
          key: 'reply',
          type: 'llm',
          name: '正式回复',
          position: { x: 820, y: 110 },
          config: {
            provider: 'openai-compatible',
            model: 'deepseek-flash',
            promptTemplate: '润色成正式回复：{{ vars.question }}',
            maxTokens: 1024,
          },
        },
        {
          key: 'notice',
          type: 'transform',
          name: '转人工复核',
          position: { x: 820, y: 300 },
          config: { mappings: [{ target: 'vars.outcome', template: '需人工复核' }] },
        },
        {
          key: 'end',
          type: 'end',
          name: '收口',
          position: { x: 1080, y: 200 },
          config: {
            resultMapping: {
              answer: '{{ nodes.draft.output.text }}',
              question: '{{ vars.question }}',
            },
          },
        },
      ],
      edges: [
        { from: 'start', to: 'draft' },
        { from: 'draft', to: 'review' },
        { from: 'review', to: 'reply', branch: 'approved' },
        { from: 'review', to: 'notice', branch: 'rejected' },
        { from: 'reply', to: 'end' },
        { from: 'notice', to: 'end' },
      ],
    },
  },
  {
    id: 'parallel',
    name: '双角度分析后汇总',
    description: '两条分支分别做事实梳理与风险提示，汇合后交给模型总结',
    dsl: {
      dslVersion: '1.0',
      variables: {
        ...question,
        kbNote: { type: 'string' as const },
        crmNote: { type: 'string' as const },
      },
      nodes: [
        { key: 'start', type: 'start', name: '入口', position: { x: 60, y: 200 }, config: {} },
        {
          key: 'gather',
          type: 'parallel',
          name: '并行分析',
          position: { x: 320, y: 200 },
          config: {
            branches: {
              fact: {
                nodes: [
                  {
                    key: 'fact_llm',
                    type: 'llm',
                    config: {
                      provider: 'openai-compatible',
                      model: 'deepseek-flash',
                      promptTemplate: '只列出与问题相关的事实要点：{{ vars.question }}',
                      maxTokens: 512,
                    },
                  },
                  {
                    key: 'fact_done',
                    type: 'transform',
                    config: { mappings: [{ target: 'vars.kbNote', template: '{{ nodes.fact_llm.output.text }}' }] },
                  },
                ],
                edges: [{ from: 'fact_llm', to: 'fact_done' }],
              },
              risk: {
                nodes: [
                  {
                    key: 'risk_llm',
                    type: 'llm',
                    config: {
                      provider: 'openai-compatible',
                      model: 'deepseek-flash',
                      promptTemplate: '指出回答这个问题需要注意的风险与前提：{{ vars.question }}',
                      maxTokens: 512,
                    },
                  },
                  {
                    key: 'risk_done',
                    type: 'transform',
                    config: { mappings: [{ target: 'vars.crmNote', template: '{{ nodes.risk_llm.output.text }}' }] },
                  },
                ],
                edges: [{ from: 'risk_llm', to: 'risk_done' }],
              },
            },
            join: 'all',
            maxConcurrency: 4,
          },
        },
        {
          key: 'summary',
          type: 'llm',
          name: '汇总',
          position: { x: 620, y: 200 },
          config: {
            provider: 'openai-compatible',
            model: 'deepseek-flash',
            promptTemplate: '结合知识库与客户记录回答：{{ vars.question }}',
            maxTokens: 1024,
          },
        },
        {
          key: 'end',
          type: 'end',
          name: '收口',
          position: { x: 900, y: 200 },
          config: { resultMapping: { answer: '{{ nodes.summary.output.text }}' } },
        },
      ],
      edges: [
        { from: 'start', to: 'gather' },
        { from: 'gather', to: 'summary' },
        { from: 'summary', to: 'end' },
      ],
    },
  },
]
