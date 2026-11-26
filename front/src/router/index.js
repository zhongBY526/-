import { createRouter, createWebHistory } from 'vue-router'
const routes = [
    { path: '/login', component: () => import('@/views/LoginView.vue') },
    { path: '/', redirect: '/kb' },
    { path: '/kb', component: () => import('@/views/KbManageView.vue'), meta: { auth: true } },
    { path: '/kb/chat', component: () => import('@/views/KbChatView.vue'), meta: { auth: true } },
    { path: '/workflow', component: () => import('@/views/WorkflowView.vue'), meta: { auth: true } },
    // 旧页面保留，不强制登录
    { path: '/chat', component: () => import('@/views/ChatViews.vue') },
    { path: '/extract', component: () => import('@/views/ExtractView.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach((to) => {
    if (to.meta.auth && !localStorage.getItem('token')) {
        return '/login'
    }
})

export default router