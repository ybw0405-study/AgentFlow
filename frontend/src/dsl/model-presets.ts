/** 市面常见的 OpenAI 兼容模型预设。
 *
 * 这里只放「公开可查的端点地址 + 常见模型别名」，不含任何密钥；端点与模型名
 * 在向导里都可编辑，服务商控制台才是最终依据。凭据始终走 /authorize 以密文落库，
 * 画布与 DSL 里只有 connectionId，绝不出现 URL 或 Key（安全边界不变）。
 */

export type PresetGroup = 'recommended' | 'domestic' | 'overseas' | 'local'

export interface ModelPreset {
  id: string
  vendor: string
  /** 厂商标识：1~2 个 ASCII 字符，用 CSS 方块呈现，不引入图片与原始色值。 */
  mark: string
  label: string
  /** 默认下发的 model id。 */
  model: string
  /** 候选项，会与探测到的 /models 结果合并。 */
  models: string[]
  baseUrl: string
  /** 申请 API Key 的控制台地址；本地服务可为空。 */
  keyUrl: string
  keyHint: string
  group: PresetGroup
  tag?: string
  note: string
  /** 不需要 API Key（例如本地 Ollama）。 */
  noKey?: boolean
  /** 需要服务端显式放行的开关，界面上要提醒，避免用户以为「连不上是坏了」。 */
  needsServerOptIn?: boolean
}

export const MODEL_PRESET_GROUPS: { id: PresetGroup; label: string }[] = [
  { id: 'recommended', label: '常用推荐' },
  { id: 'domestic', label: '国内其他' },
  { id: 'overseas', label: '海外服务' },
  { id: 'local', label: '本地 / 自托管' },
]

/** 端点来自各家公开的 OpenAI 兼容文档；模型名会随版本变化，界面允许直接改。 */
export const MODEL_PRESETS: readonly ModelPreset[] = [
  {
    id: 'hunyuan-hy4',
    vendor: '腾讯混元',
    mark: 'HY',
    label: '混元 Hy4',
    model: 'hy4',
    models: ['hy4', 'hy4-flash'],
    baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1',
    keyUrl: 'https://cloud.tencent.com/product/hunyuan',
    keyHint: '腾讯云 TokenHub 控制台创建 API Key',
    group: 'recommended',
    tag: '推荐',
    note: '混元旗舰，OpenAI 兼容接口，支持视觉与工具调用',
  },
  {
    id: 'deepseek',
    vendor: '深度求索',
    mark: 'DS',
    label: 'DeepSeek',
    model: 'deepseek-chat',
    models: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-flash'],
    baseUrl: 'https://api.deepseek.com/v1',
    keyUrl: 'https://platform.deepseek.com',
    keyHint: 'DeepSeek 开放平台创建 API Key',
    group: 'recommended',
    tag: '热门',
    note: 'reasoner 为思考模式；别名会随版本升级，以控制台为准',
  },
  {
    id: 'zhipu-glm',
    vendor: '智谱 AI',
    mark: 'G',
    label: '智谱 GLM',
    model: 'glm-4.6',
    models: ['glm-4.6', 'glm-4.5', 'glm-4.5-air', 'glm-5.3', 'glm-5.3-flash'],
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    keyUrl: 'https://open.bigmodel.cn',
    keyHint: '智谱开放平台获取 API Key',
    group: 'recommended',
    tag: '热门',
    note: '路径是 /api/paas/v4，不是 /v1，最容易填错的一处',
  },
  {
    id: 'moonshot-kimi',
    vendor: '月之暗面',
    mark: 'K',
    label: 'Kimi',
    model: 'kimi-k2.5',
    models: ['kimi-k2.5', 'kimi-k2.5-thinking', 'kimi-k3.1'],
    baseUrl: 'https://api.moonshot.cn/v1',
    keyUrl: 'https://platform.moonshot.cn',
    keyHint: 'Moonshot 开放平台创建 API Key',
    group: 'recommended',
    tag: '长上下文',
    note: '长文档与资料整理常用，模型名以控制台为准',
  },
  {
    id: 'qwen',
    vendor: '阿里云百炼',
    mark: 'Q',
    label: '通义千问',
    model: 'qwen-plus',
    models: ['qwen-plus', 'qwen-max', 'qwen3-max', 'qwen-turbo'],
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    keyUrl: 'https://bailian.console.aliyun.com',
    keyHint: '百炼控制台创建 API Key',
    group: 'domestic',
    note: '必须带 /compatible-mode/v1，原生 DashScope 路径不兼容',
  },
  {
    id: 'volcengine-doubao',
    vendor: '火山方舟',
    mark: 'D',
    label: '豆包',
    model: 'doubao-seed-2.0-pro',
    models: ['doubao-seed-2.0-pro', 'doubao-seed-2.0-lite', 'doubao-seed-2.0-mini'],
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    keyUrl: 'https://console.volcengine.com/ark',
    keyHint: '火山方舟控制台创建 API Key',
    group: 'domestic',
    note: '生产环境通常填控制台的推理接入点 ID（ep- 开头）而非模型名',
  },
  {
    id: 'minimax',
    vendor: 'MiniMax',
    mark: 'M',
    label: 'MiniMax',
    model: 'MiniMax-M2',
    models: ['MiniMax-M2', 'MiniMax-M2.5'],
    baseUrl: 'https://api.minimaxi.com/v1',
    keyUrl: 'https://platform.minimaxi.com',
    keyHint: 'MiniMax 开放平台创建 API Key',
    group: 'domestic',
    note: '模型名区分大小写，以控制台显示为准',
  },
  {
    id: 'siliconflow',
    vendor: '硅基流动',
    mark: 'SF',
    label: '硅基流动',
    model: 'deepseek-ai/DeepSeek-V3',
    models: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen3-8B', 'THUDM/glm-4-9b-chat'],
    baseUrl: 'https://api.siliconflow.cn/v1',
    keyUrl: 'https://cloud.siliconflow.cn',
    keyHint: '硅基流动控制台创建 API Key',
    group: 'domestic',
    tag: '聚合平台',
    note: '一个 Key 调用多家开源模型，模型名带命名空间',
  },
  {
    id: 'stepfun',
    vendor: '阶跃星辰',
    mark: 'S',
    label: 'Step',
    model: 'step-3.5-flash',
    models: ['step-3.5-flash'],
    baseUrl: 'https://api.stepfun.com/v1',
    keyUrl: 'https://platform.stepfun.com',
    keyHint: '阶跃星辰开放平台创建 API Key',
    group: 'domestic',
    tag: '低延迟',
    note: '短链路、高并发场景合适',
  },
  {
    id: 'openai',
    vendor: 'OpenAI',
    mark: 'O',
    label: 'OpenAI',
    model: 'gpt-4o-mini',
    models: ['gpt-4o-mini', 'gpt-4o', 'o3-mini'],
    baseUrl: 'https://api.openai.com/v1',
    keyUrl: 'https://platform.openai.com/api-keys',
    keyHint: 'OpenAI 控制台创建 API Key',
    group: 'overseas',
    note: '需要可访问海外网络的出网环境',
  },
  {
    id: 'ollama',
    vendor: 'Ollama',
    mark: 'OL',
    label: '本地 Ollama',
    model: 'qwen2.5:7b',
    models: ['qwen2.5:7b', 'llama3.1:8b'],
    baseUrl: 'http://localhost:11434/v1',
    keyUrl: '',
    keyHint: '本地模型无需 API Key',
    group: 'local',
    tag: '需服务端放行',
    note: 'http + 回环地址默认被沙箱拦截，需开启 AF_SANDBOX_ALLOW_LOOPBACK 与 AF_SANDBOX_ALLOW_HTTP',
    noKey: true,
    needsServerOptIn: true,
  },
]

/** 搜索同时匹配中文名、厂商、模型别名与端点，避免只会拼英文才找得到。 */
export function searchModelPresets(query: string): ModelPreset[] {
  const q = query.trim().toLowerCase()
  if (!q) return [...MODEL_PRESETS]
  return MODEL_PRESETS.filter((p) =>
    [p.label, p.vendor, p.model, p.baseUrl, p.note, ...p.models]
      .join(' ')
      .toLowerCase()
      .includes(q),
  )
}

/** 反查展示名：节点上手填了 hy4 之类，也显示成「混元 Hy4」而不是一串 id。 */
export function findPresetByModel(model: string): ModelPreset | null {
  const target = model.trim().toLowerCase()
  if (!target) return null
  return (
    MODEL_PRESETS.find(
      (p) =>
        p.model.toLowerCase() === target ||
        p.models.some((m) => m.toLowerCase() === target) ||
        p.id === target,
    ) ?? null
  )
}

/** 模板里的 mock-* 是离线演示模型，不该显示成一串看起来「没配好」的占位符。 */
export function isBuiltinDemoModel(config: Record<string, unknown>): boolean {
  const model = String(config.model ?? '').trim().toLowerCase()
  const provider = String(config.provider ?? '')
  return model.startsWith('mock') || provider === 'mock'
}

/** 卡片上的「模型」字段展示值：演示模型给中文说明，预设模型给中文名，其余显示原值。 */
export function displayModelName(config: Record<string, unknown>): string {
  const model = String(config.model ?? '').trim()
  if (isBuiltinDemoModel(config)) return '内置演示模型 · 离线可跑'
  if (!model) return '未选择模型'
  return findPresetByModel(model)?.label ?? model
}
