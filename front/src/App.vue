<script setup>
import { ref } from 'vue'
import ChatViews from '@/views/ChatViews.vue'
import ExtractView from '@/views/ExtractView.vue'

const active = ref('chat')
</script>

<template>
  <div class="tabs">
    <button :class="{ active: active === 'chat' }" @click="active = 'chat'">
      流式对话
    </button>
    <button :class="{ active: active === 'extract' }" @click="active = 'extract'">
      公文抽取
    </button>
  </div>

  <ChatViews v-if="active === 'chat'" />
  <ExtractView v-else />
</template>

<style>
body {
  margin: 0;
}

.tabs {
  display: flex;
  justify-content: center;
  gap: 8px;
  padding: 16px;
  border-bottom: 1px solid #eee;
}

.tabs button {
  padding: 8px 18px;
  border: 1px solid #ddd;
  background: #fff;
  cursor: pointer;
  border-radius: 4px;
}

.tabs button.active {
  background: #2563eb;
  color: #fff;
  border-color: #2563eb;
}
</style>