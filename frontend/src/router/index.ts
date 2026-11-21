import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要管理员角色（当前空间 membership.role 为 OWNER/ADMIN）才能进入。 */
    requiresAdmin?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/', redirect: '/workflows' },
    { path: '/workflows', name: 'workflows', component: () => import('@/views/WorkflowListView.vue') },
    {
      path: '/workflows/:id/edit',
      name: 'editor',
      component: () => import('@/views/CanvasEditorView.vue'),
    },
    {
      path: '/workflows/:id/versions',
      name: 'versions',
      component: () => import('@/views/VersionsView.vue'),
    },
    {
      path: '/workflows/:id/triggers',
      name: 'triggers',
      component: () => import('@/views/TriggersView.vue'),
    },
    {
      path: '/executions',
      name: 'executions',
      component: () => import('@/views/ExecutionListView.vue'),
      props: (route) => {
        const q = route.query.definitionId
        const value = Array.isArray(q) ? q[0] : q
        return { definitionId: typeof value === 'string' ? value : undefined }
      },
    },
    {
      path: '/executions/:id',
      name: 'execution-detail',
      component: () => import('@/views/ExecutionDetailView.vue'),
    },
    { path: '/connectors', name: 'connectors', component: () => import('@/views/ConnectorsView.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue') },
    { path: '/approvals', name: 'approvals', component: () => import('@/views/ApprovalsView.vue') },
    { path: '/evals', name: 'evals', component: () => import('@/views/EvalsView.vue') },
    { path: '/usage', name: 'usage', component: () => import('@/views/UsageView.vue') },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('@/views/AdminView.vue'),
      meta: { requiresAdmin: true },
    },
    // 兜底：任何未匹配路径都应给出明确页面，而不是空白
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.name === 'login') {
    // 已登录再访问登录页没有意义，直接回工作台
    if (auth.isAuthenticated) return { name: 'workflows' }
    return true
  }
  if (!auth.isAuthenticated) {
    const ok = await auth.restore()
    if (!ok) return { name: 'login', query: to.fullPath === '/' ? {} : { next: to.fullPath } }
  }
  // 判定必须排在会话恢复之后：isAdmin 读的是 restore() 后才知道的 memberships。
  // 本地弹回工作台只是避免非管理员落在一个必然 403 的跨租户页面，服务端逐请求校验仍是最终判据。
  if (to.meta.requiresAdmin === true && !auth.isAdmin) {
    return { name: 'workflows' }
  }
  return true
})

export default router
