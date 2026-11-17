<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const isLogin = computed(() => route.name === 'login')

async function logout() {
  await auth.logout()
  void router.push({ name: 'login' })
}
</script>

<template>
  <div class="af-shell">
    <header
      v-if="!isLogin"
      class="af-topbar"
    >
      <div class="af-brand">
        Agent<span>Flow</span>
      </div>
      <nav class="af-nav">
        <router-link to="/workflows">工作流</router-link>
        <router-link to="/executions">执行</router-link>
        <router-link to="/approvals">审批</router-link>
        <router-link to="/connectors">连接器</router-link>
        <router-link to="/evals">评测</router-link>
        <router-link to="/admin">管理</router-link>
      </nav>
      <div class="af-spacer" />
      <label
        class="af-faint"
        style="font-size: 11px"
      >SPACE</label>
      <select
        class="af-select"
        style="width: auto; min-width: 140px"
        :value="auth.currentSpaceId ?? ''"
        @change="auth.setSpace(($event.target as HTMLSelectElement).value)"
      >
        <option
          v-for="m in auth.user?.memberships ?? []"
          :key="m.tenantId"
          :value="m.tenantId"
        >
          {{ m.name }} · {{ m.role }}
        </option>
      </select>
      <span class="af-dim">{{ auth.user?.email }}</span>
      <button
        class="af-btn af-btn--sm"
        @click="logout"
      >
        退出
      </button>
    </header>
    <main class="af-main">
      <router-view />
    </main>
  </div>
</template>
