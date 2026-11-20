<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { ModelPreset } from '@/dsl/model-presets'
import {
  connectModel,
  hostOf,
  probeErrorMessage,
  validateConnectInput,
} from '@/dsl/model-connection'
import type { ConnectModelResult } from '@/dsl/model-connection'

defineOptions({ name: 'ModelConnectDialog' })

const props = defineProps<{
  /** null 表示「自定义 OpenAI 兼容端点」。 */
  preset: ModelPreset | null
  currentModel: string
}>()

const emit = defineEmits<{
  connected: [result: ConnectModelResult]
  back: []
  close: []
}>()

const form = reactive({
  name: props.preset ? `${props.preset.label} 连接` : '自定义模型',
  baseUrl: props.preset?.baseUrl ?? '',
  model: props.preset?.model ?? '',
  models: (props.preset?.models ?? []).join(', '),
  allowedHosts: props.preset ? hostOf(props.preset.baseUrl) : '',
  secret: '',
  noKeyAllowed: props.preset?.noKey ?? false,
  allowWithoutProbe: false,
})

const advancedOpen = ref(false)
const showSecret = ref(false)
const busy = ref(false)
const result = ref<ConnectModelResult | null>(null)

const title = computed(() =>
  props.preset ? `接入 ${props.preset.label}` : '自定义 OpenAI 兼容端点',
)

const canSubmit = computed(() => !busy.value)

function parseList(raw: string): string[] {
  return raw
    .split(/[,，\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function buildInput() {
  return {
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    allowedHosts: parseList(form.allowedHosts),
    models: parseList(form.models),
    model: form.model.trim(),
    secret: form.secret.trim(),
    noKeyAllowed: form.noKeyAllowed,
    allowWithoutProbe: form.allowWithoutProbe,
  }
}

async function submit(): Promise<void> {
  result.value = null
  const input = buildInput()
  const problem = validateConnectInput(input)
  if (problem) {
    result.value = { ok: false, connectorId: '', name: input.name, model: input.model, models: [], message: problem }
    return
  }
  busy.value = true
  try {
    const outcome = await connectModel(input)
    if (outcome.ok) {
      emit('connected', outcome)
      return
    }
    result.value = outcome
  } finally {
    busy.value = false
  }
}

function onBaseUrlChange(value: string): void {
  form.baseUrl = value
  // 允许域名留空或仍是上一条预设推导值时，跟随 URL 自动更新，省一次手填
  const previousHost = props.preset ? hostOf(props.preset.baseUrl) : ''
  if (!form.allowedHosts.trim() || form.allowedHosts.trim() === previousHost) {
    form.allowedHosts = hostOf(value)
  }
}
</script>

<template>
  <div
    class="af-connect-backdrop"
    @click.self="emit('close')"
  >
    <div class="af-connect">
      <div class="af-connect__head">
        <div>
          <div class="af-connect__title">
            {{ title }}
          </div>
          <div class="af-connect__sub">
            {{ preset
              ? `${preset.vendor} · ${preset.note}`
              : '任意实现了 /chat/completions 的服务都能接，端点与模型名自己填' }}
          </div>
        </div>
        <button
          class="af-connect__close"
          @click="emit('close')"
        >
          关闭
        </button>
      </div>

      <div
        v-if="preset?.needsServerOptIn"
        class="af-connect__notice"
      >
        本地 / 回环端点默认被沙箱拦截，需在服务端开启
        <span class="mono">AF_SANDBOX_ALLOW_LOOPBACK</span> 与
        <span class="mono">AF_SANDBOX_ALLOW_HTTP</span>；否则测试会返回「出网被沙箱拦截」。
      </div>

      <div class="af-connect__body">
        <label class="af-label">连接名称</label>
        <input
          v-model="form.name"
          class="af-input"
          placeholder="例如：混元 Hy4 连接"
        >

        <div class="af-connect__label-row">
          <label class="af-label">Base URL（OpenAI 兼容端点）</label>
          <span class="af-connect__tip">可编辑 · 以服务商文档为准</span>
        </div>
        <input
          class="af-input mono"
          :value="form.baseUrl"
          placeholder="https://api.example.com/v1"
          spellcheck="false"
          @input="onBaseUrlChange(($event.target as HTMLInputElement).value)"
        >

        <div class="af-connect__label-row">
          <label class="af-label">模型名</label>
          <span class="af-connect__tip">实际下发给端点的 model</span>
        </div>
        <input
          v-model="form.model"
          class="af-input mono"
          list="af-connect-models"
          placeholder="hy4 / deepseek-chat / glm-4.6…"
          spellcheck="false"
        >
        <datalist id="af-connect-models">
          <option
            v-for="m in parseList(form.models)"
            :key="m"
            :value="m"
          />
        </datalist>

        <div class="af-connect__label-row">
          <label class="af-label">API Key</label>
          <a
            v-if="preset?.keyUrl"
            class="af-connect__link"
            :href="preset.keyUrl"
            target="_blank"
            rel="noopener noreferrer"
          >去申请 Key</a>
          <span
            v-else-if="preset"
            class="af-connect__tip"
          >{{ preset.keyHint }}</span>
        </div>
        <div class="af-connect__secret">
          <input
            v-model="form.secret"
            class="af-input mono"
            :type="showSecret ? 'text' : 'password'"
            :placeholder="form.noKeyAllowed ? '本地服务可留空' : 'sk-…'"
            autocomplete="off"
            spellcheck="false"
          >
          <button
            class="af-connect__eye"
            @click="showSecret = !showSecret"
          >
            {{ showSecret ? '隐藏' : '显示' }}
          </button>
        </div>

        <button
          class="af-connect__advanced"
          @click="advancedOpen = !advancedOpen"
        >
          {{ advancedOpen ? '收起高级选项' : '高级选项（允许域名 / 模型清单 / 兼容开关）' }}
        </button>
        <div
          v-if="advancedOpen"
          class="af-connect__advanced-body"
        >
          <label class="af-label">允许域名（逗号分隔）</label>
          <input
            v-model="form.allowedHosts"
            class="af-input mono"
            placeholder="api.hunyuan.cloud.tencent.com"
          >
          <label class="af-label">候选模型清单（逗号分隔）</label>
          <input
            v-model="form.models"
            class="af-input mono"
            placeholder="hy4, hy4-flash"
          >
          <label class="af-connect__check">
            <input
              v-model="form.noKeyAllowed"
              type="checkbox"
            >
            该端点不需要 API Key（本地服务）
          </label>
          <label class="af-connect__check">
            <input
              v-model="form.allowWithoutProbe"
              type="checkbox"
            >
            端点不提供 GET /models 时，仍按上面填的模型名保存
          </label>
          <p class="af-connect__tip">
            部分兼容端点没有模型列表接口；勾选后不再把「拉不到清单」当成失败，仍会校验凭据与连通性。
          </p>
        </div>

        <div
          v-if="result && !result.ok"
          class="af-connect__result af-connect__result--fail"
        >
          <span class="af-connect__result-title">没能连上</span>
          <span>{{ result.message ?? probeErrorMessage(result.errorCode ?? null) }}</span>
        </div>
      </div>

      <div class="af-connect__foot">
        <button
          class="af-btn af-btn--sm"
          @click="emit('back')"
        >
          返回列表
        </button>
        <span class="af-spacer" />
        <span class="af-connect__safe">密钥以 AES-256-GCM 密文存储，画布与日志均不含明文</span>
        <button
          class="af-btn af-btn--sm"
          @click="emit('close')"
        >
          取消
        </button>
        <button
          class="af-btn af-btn--primary af-btn--sm"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ busy ? '正在验证并连接…' : '测试并连接' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.af-connect-backdrop {
  position: fixed;
  inset: 0;
  background: var(--af-backdrop);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 64px;
  z-index: 30;
}

.af-connect {
  width: 560px;
  max-width: calc(100% - 32px);
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  background: var(--af-surface);
  border: 1px solid var(--af-border-strong);
  border-radius: var(--af-radius);
  overflow: hidden;
}

.af-connect__head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px 10px;
  border-bottom: 1px solid var(--af-border);
}

.af-connect__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--af-text);
}

.af-connect__sub {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--af-text-faint);
  line-height: 1.5;
}

.af-connect__close {
  flex: none;
  background: transparent;
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  color: var(--af-text-dim);
  font-size: 11px;
  padding: 3px 8px;
  cursor: pointer;
}

.af-connect__close:hover {
  color: var(--af-accent);
  border-color: var(--af-accent-border);
}

.af-connect__notice {
  margin: 10px 16px 0;
  padding: 8px 10px;
  background: var(--af-raised);
  border-left: 2px solid var(--af-node-control);
  border-radius: var(--af-radius-sm);
  font-size: 11px;
  line-height: 1.6;
  color: var(--af-text-dim);
}

.af-connect__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 16px 12px;
}

.af-connect__label-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.af-connect__tip {
  font-size: 10.5px;
  color: var(--af-text-faint);
}

.af-connect__link {
  font-size: 11px;
  color: var(--af-accent);
  text-decoration: none;
}

.af-connect__link:hover {
  text-decoration: underline;
}

.af-connect__secret {
  display: flex;
  gap: 6px;
  align-items: center;
}

.af-connect__eye {
  flex: none;
  padding: 6px 8px;
  background: var(--af-panel);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  color: var(--af-text-dim);
  font-size: 11px;
  cursor: pointer;
}

.af-connect__eye:hover {
  color: var(--af-accent);
}

.af-connect__advanced {
  display: block;
  width: 100%;
  margin-top: 12px;
  padding: 6px 0;
  background: transparent;
  border: none;
  border-top: 1px solid var(--af-border);
  color: var(--af-text-dim);
  font-size: 11px;
  text-align: left;
  cursor: pointer;
}

.af-connect__advanced:hover {
  color: var(--af-accent);
}

.af-connect__advanced-body {
  padding-bottom: 4px;
}

.af-connect__check {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--af-text-dim);
  cursor: pointer;
}

.af-connect__check input {
  accent-color: var(--af-accent);
}

.af-connect__result {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: var(--af-radius-sm);
  font-size: 11.5px;
  line-height: 1.5;
}

.af-connect__result--fail {
  background: var(--af-danger-dim);
  border-left: 2px solid var(--af-danger);
  color: var(--af-danger);
}

.af-connect__result-title {
  font-weight: 600;
}

.af-connect__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  border-top: 1px solid var(--af-border);
}

.af-connect__safe {
  font-size: 10.5px;
  color: var(--af-text-faint);
}
</style>
