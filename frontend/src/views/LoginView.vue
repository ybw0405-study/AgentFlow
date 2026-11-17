<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'

const auth = useAuthStore()
const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref<{ code: string; traceId?: string } | null>(null)
const busy = ref(false)

async function submit() {
  error.value = null
  busy.value = true
  try {
    await auth.login(email.value, password.value)
    void router.push('/workflows')
  } catch (e) {
    if (e instanceof ApiError) {
      // 不区分账号是否存在（安全口径）：统一提示
      error.value = { code: e.code, traceId: e.traceId }
    } else {
      error.value = { code: 'NETWORK' }
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div style="display: grid; place-items: center; height: 100%">
    <form
      class="af-panel"
      style="width: 340px; padding: 28px"
      @submit.prevent="submit"
    >
      <div
        class="af-brand"
        style="font-size: 18px; margin-bottom: 4px"
      >
        Agent<span>Flow</span>
      </div>
      <p
        class="af-faint"
        style="margin: 0 0 20px"
      >
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
      >
      <div
        v-if="error"
        class="af-error-box"
        style="margin-top: 12px"
      >
        登录失败（{{ error.code }}）
        <span
          v-if="error.traceId"
          class="af-faint mono"
        > · trace {{ error.traceId }}</span>
      </div>
      <button
        class="af-btn af-btn--primary"
        style="width: 100%; margin-top: 16px"
        :disabled="busy"
        type="submit"
      >
        {{ busy ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>
