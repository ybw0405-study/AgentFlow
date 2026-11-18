<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { errorDebugInfo, errorText } from '@/api/errors'
import type { DemoAccount } from '@/types/api'

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

async function submit(): Promise<void> {
  errorMain.value = ''
  errorDebug.value = ''
  busy.value = true
  try {
    await auth.login(email.value, password.value)
    const next = typeof route.query.next === 'string' ? route.query.next : '/workflows'
    void router.push(next)
  } catch (e) {
    errorMain.value = errorText(e)
    errorDebug.value = errorDebugInfo(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="af-login">
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
  </div>
</template>

<style scoped>
.af-login {
  display: grid;
  place-items: center;
  height: 100%;
  padding: 24px;
}

.af-login__card {
  width: 360px;
  padding: 28px;
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
</style>
