<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { errorDebugInfo, errorText } from '@/api/errors'
import type { DemoAccount } from '@/types/api'
import BlueprintBoard from '@/components/design/BlueprintBoard.vue'
import DesignIcon from '@/components/design/DesignIcon.vue'
import IsoInstrument from '@/components/design/IsoInstrument.vue'
import TickerBar from '@/components/design/TickerBar.vue'

const tickerItems = ['DETERMINISTIC DSL', 'HUMAN-IN-THE-LOOP', 'BUDGET GUARD', 'TRACE EVERY STEP', 'MCP READY']
const capabilities = [
  { no: '01 / CANVAS', icon: 'canvas', title: '可视化编排', desc: '拖拽节点、连线与分支，画布即 DSL。', tag: 'NODE · 12 TYPES' },
  { no: '02 / ENGINE', icon: 'engine', title: '可追溯执行', desc: '每次运行都有节点级输入、输出与耗时。', tag: 'TRACE · FULL' },
  { no: '03 / GUARD', icon: 'shield', title: '治理与审批', desc: '高风险动作停在人这一侧，预算与权限同账本。', tag: 'APPROVAL · ON' },
] as const

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const email = ref('')
const password = ref('')
const errorMain = ref('')
const errorDebug = ref('')
const busy = ref(false)
const demoAccounts = ref<DemoAccount[]>([])

onMounted(async () => {
  try {
    const data = await authApi.bootstrap()
    demoAccounts.value = data.demoAccounts
  } catch {
    // 演示账号是增强信息，取不到不影响正常登录
  }
})

function fill(account: DemoAccount): void {
  email.value = account.email
  password.value = account.password
}

/** next 只接受站内绝对路径：必须以单个 / 开头，排除 //（协议相对外链）与反斜杠。 */
function safeNext(raw: unknown): string {
  if (
    typeof raw !== 'string'
    || !raw.startsWith('/')
    || raw.startsWith('//')
    || raw.includes(String.fromCharCode(92))
  ) {
    return '/workflows'
  }
  return raw
}

async function submit(): Promise<void> {
  errorMain.value = ''
  errorDebug.value = ''
  busy.value = true
  try {
    await auth.login(email.value, password.value)
    void router.push(safeNext(route.query.next))
  } catch (e) {
    errorMain.value = errorText(e)
    errorDebug.value = errorDebugInfo(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="af-login af-show-shell">
    <section class="af-show-hero">
      <div class="af-show-hero__grid">
        <div class="af-reveal is-in">
          <span class="af-fig-tag">FIG. A3 / PRECISION BUILD</span>
          <h1 class="af-display">
            Agent<span class="bl">Flow</span>
            <span class="af-display__zh">把智能体流程做成一台精密仪器</span>
          </h1>
          <p class="af-show-sub">
            从节点编排到受审批约束的执行，每一步都可追溯、可复现、可计量。
          </p>
          <div class="af-cta-row">
            <span class="af-kicker">CONTROL CONSOLE / 01</span>
            <span class="af-mono-label">v0.6 · PRECISION EDITION</span>
          </div>
          <div class="af-stats">
            <div class="af-stat">
              <b>12</b>
              <span>NODE TYPES</span>
            </div>
            <div class="af-stat">
              <b>100<em>%</em></b>
              <span>TRACED RUNS</span>
            </div>
            <div class="af-stat">
              <b>3<em>色</em></b>
              <span>状态语义</span>
            </div>
          </div>
        </div>
        <div class="af-reveal is-in">
          <BlueprintBoard />
          <div style="margin-top: 12px">
            <IsoInstrument />
          </div>
        </div>
      </div>
    </section>

    <TickerBar :items="tickerItems" />

    <div class="af-login__lower">
      <form
        class="af-panel af-login__card"
        @submit.prevent="submit"
      >
        <div class="af-brand af-login__brand">
          Agent<span>Flow</span>
        </div>
        <p class="af-faint af-login__sub">
          可视化 Agent 工作流编排平台
        </p>

        <label
        class="af-label"
        for="login-email"
      >邮箱</label>
      <input
        id="login-email"
        v-model="email"
        class="af-input"
        type="email"
        required
        autocomplete="username"
        placeholder="you@example.com"
      >

      <label
        class="af-label"
        for="login-password"
      >密码</label>
      <input
        id="login-password"
        v-model="password"
        class="af-input"
        type="password"
        required
        autocomplete="current-password"
        placeholder="请输入密码"
      >

      <div
        v-if="errorMain"
        class="af-error-box af-login__error"
        role="alert"
      >
        <span class="af-login__error-main">{{ errorMain }}</span>
        <span
          v-if="errorDebug"
          class="af-faint mono af-login__debug"
        >{{ errorDebug }}</span>
      </div>

      <button
        class="af-btn af-btn--primary af-login__submit"
        :disabled="busy"
        type="submit"
      >
        {{ busy ? '登录中…' : '登录' }}
      </button>

      <div
        v-if="demoAccounts.length"
        class="af-login__demo"
      >
        <p class="af-faint af-login__demo-title">
          演示账号（点击填入）
        </p>
        <button
          v-for="account in demoAccounts"
          :key="account.email"
          class="af-login__demo-item"
          type="button"
          @click="fill(account)"
        >
          <span class="af-login__demo-label">{{ account.label }}</span>
          <span class="af-faint mono">{{ account.email }}</span>
        </button>
      </div>
      </form>

      <div class="af-feature-grid af-login__features">
        <article
          v-for="cap in capabilities"
          :key="cap.no"
          class="af-feature-cell af-reveal"
        >
          <div class="af-feature-cell__no">
            <span>{{ cap.no }}</span>
            <span>→</span>
          </div>
          <div class="af-feature-cell__ico">
            <DesignIcon :name="cap.icon" />
          </div>
          <h3>{{ cap.title }}</h3>
          <p>{{ cap.desc }}</p>
          <div class="af-feature-cell__tag"><b>{{ cap.tag }}</b></div>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.af-login {
  height: 100%;
}

.af-login__lower {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
  max-width: 1240px;
  margin: 24px auto 0;
}

.af-login__card {
  width: 100%;
  padding: 28px;
  box-shadow: var(--af-shadow);
}

.af-login__features.af-feature-grid {
  margin: 0;
  grid-template-columns: repeat(3, 1fr);
}

.af-login__brand {
  font-size: 18px;
}

.af-login__sub {
  margin: 4px 0 20px;
}

.af-login__error {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 14px;
}

.af-login__debug {
  font-size: 11px;
}

.af-login__submit {
  width: 100%;
  margin-top: 16px;
}

.af-login__demo {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--af-border);
}

.af-login__demo-title {
  margin: 0 0 8px;
  font-size: 12px;
}

.af-login__demo-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 8px 10px;
  font: inherit;
  color: var(--af-text-dim);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--af-radius-sm);
  transition: background var(--af-motion), border-color var(--af-motion);
}

.af-login__demo-item:hover {
  background: var(--af-accent-dim);
  border-color: var(--af-accent-border);
}

.af-login__demo-label {
  font-size: 12px;
  color: var(--af-text);
}

@media (max-width: 960px) {
  .af-login__lower {
    grid-template-columns: 1fr;
  }
  .af-login__features.af-feature-grid {
    grid-template-columns: 1fr;
  }
}
</style>
