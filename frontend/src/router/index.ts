import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
      path: '/executions',
      name: 'executions',
      component: () => import('@/views/ExecutionListView.vue'),
    },
    {
      path: '/executions/:id',
      name: 'execution-detail',
      component: () => import('@/views/ExecutionDetailView.vue'),
    },
    { path: '/connectors', name: 'connectors', component: () => import('@/views/ConnectorsView.vue') },
    { path: '/approvals', name: 'approvals', component: () => import('@/views/ApprovalsView.vue') },
    { path: '/evals', name: 'evals', component: () => import('@/views/EvalsView.vue') },
    { path: '/admin', name: 'admin', component: () => import('@/views/AdminView.vue') },
  ],
})

router.beforeEach(async (to) => {
  if (to.name === 'login') return true
  const auth = useAuthStore()
  if (!auth.isAuthenticated) {
    const ok = await auth.restore()
    if (!ok) return { name: 'login' }
  }
  return true
})

export default router
