<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

function back(): void {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/workflows')
  }
}
</script>

<template>
  <div class="af-page af-notfound">
    <p class="af-notfound__code">
      404
    </p>
    <h1 class="af-notfound__title">
      页面不存在
    </h1>
    <p class="af-faint af-notfound__desc">
      路径 <code class="mono">{{ route.fullPath }}</code> 没有对应的页面，可能已被移动或删除。
    </p>
    <div class="af-notfound__actions">
      <button
        class="af-btn"
        type="button"
        @click="back"
      >
        返回上一页
      </button>
      <button
        class="af-btn af-btn--primary"
        type="button"
        @click="router.push('/workflows')"
      >
        回到工作流
      </button>
    </div>
  </div>
</template>

<style scoped>
.af-notfound {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 60vh;
  text-align: center;
}

.af-notfound__code {
  margin: 0;
  font-family: var(--af-mono);
  font-size: 64px;
  font-weight: 600;
  line-height: 1;
  color: var(--af-surface);
  letter-spacing: -2px;
  /* 大字做底纹，不抢标题注意力 */
  text-shadow: 0 0 0 var(--af-border);
  -webkit-text-stroke: 1px var(--af-border-strong);
}

.af-notfound__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--af-text);
}

.af-notfound__desc {
  max-width: 460px;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-all;
}

.af-notfound__actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
</style>
