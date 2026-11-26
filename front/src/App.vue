<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <nav v-if="auth.isLoggedIn" class="top-nav">
    <router-link to="/kb">知识库管理</router-link>
    <router-link to="/kb/chat">知识库问答</router-link>
    <router-link to="/workflow">公文工作流</router-link>
    <router-link to="/chat">流式对话</router-link>
    <router-link to="/extract">公文抽取</router-link>
    <span class="nav-spacer"></span>
    <span class="nav-user">{{ auth.user?.username }}</span>
    <button class="nav-logout" @click="logout">退出登录</button>
  </nav>
  <router-view />
</template>

<style>
body {
  margin: 0;
  background: #f9fafb;
  font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
}

.top-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
}

.top-nav a {
  padding: 8px 18px;
  border: 1px solid #ddd;
  background: #fff;
  color: #374151;
  text-decoration: none;
  border-radius: 4px;
  font-size: 14px;
}

.top-nav a.router-link-exact-active {
  background: #2563eb;
  color: #fff;
  border-color: #2563eb;
}

.nav-spacer {
  flex: 1;
}

.nav-user {
  color: #6b7280;
  font-size: 14px;
}

.nav-logout {
  padding: 8px 14px;
  border: 1px solid #ddd;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
</style>