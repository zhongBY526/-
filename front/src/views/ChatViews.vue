<script setup>
import { ref } from 'vue'

const input = ref('')
const style = ref('严谨')
const content = ref('')
const error = ref('')
const streaming = ref(false)

const messageId = ref('')
const firstTokenMs = ref(0)
const serverFirstTokenMs = ref(0)
const finishReason = ref('')
const composing = ref(false)

let controller = null
let startedAt = 0

async function send() {
  if (!input.value.trim() || streaming.value) return

  content.value = ''
  error.value = ''
  messageId.value = ''
  firstTokenMs.value = 0
  serverFirstTokenMs.value = 0
  finishReason.value = ''
  streaming.value = true
  startedAt = performance.now()
  controller = new AbortController()
  try {
    const response = await fetch('/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: input.value,
        style: style.value
      }),
      signal: controller.signal
    })

    if (!response.ok || !response.body) {
      throw new Error(`HTTP ${response.status}`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      // 流式解码：半个中文字符会留到下一次
      buffer += decoder.decode(value, { stream: true })
      // 空行分帧；最后一段可能不完整，留在 buffer
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        let event = 'message'
        const dataLines = []
        for (const line of frame.split(/\r?\n/)) {
          if (!line || line.startsWith(':')) continue
          if (line.startsWith('event:')) {
            event = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''))
          }
        }
        if (dataLines.length === 0) continue
        const raw = dataLines.join('\n')
        let data
        try {
          data = JSON.parse(raw)
        } catch {
          data = raw
        }
        if (event === 'meta') {
          messageId.value = data.messageId ?? ''
        } else if (event === 'token') {
          if (firstTokenMs.value === 0) {
            firstTokenMs.value = Math.round(performance.now() - startedAt)
          }
          content.value += data.delta ?? ''
        } else if (event === 'done') {
          serverFirstTokenMs.value = data.firstTokenMs ?? 0
          finishReason.value = data.finishReason ?? ''
        } else if (event === 'error') {
          error.value = data.message ?? '模型调用失败'
        }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      error.value = e.message
    }
  } finally {
    streaming.value = false
    controller = null
  }
}
function stop() {
  controller?.abort()
}

function onEnter() {
  if (composing.value) return
  send()
}

function onCompositionStart() {
  composing.value = true
}

function onCompositionEnd() {
  composing.value = false
}
</script>

<template>
  <div class="page">
    <h1>流式对话测试</h1>

    <div class="row">
      <label>风格：</label>
      <select v-model="style">
        <option>严谨</option>
        <option>温和</option>
        <option>简洁</option>
      </select>
    </div>
    <textarea
        v-model="input"
        placeholder="输入问题，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="onEnter"
        @compositionstart="onCompositionStart"
        @compositionend="onCompositionEnd"
    ></textarea>
    <div class="row">
      <button :disabled="streaming || !input.trim()" @click="send">发送</button>
      <button v-if="streaming" @click="stop">停止</button>
    </div>
    <div class="meta">
      <span>消息 ID：{{ messageId || '-' }}</span>
      <span>前端首字：{{ firstTokenMs }} ms</span>
      <span>后端首字：{{ serverFirstTokenMs }} ms</span>
      <span>结束原因：{{ finishReason || '-' }}</span>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <pre class="output">{{ content }}</pre>
  </div>
</template>
<style scoped>
.page {
  max-width: 820px;
  margin: 40px auto;
  padding: 0 16px;
  font-family: system-ui, sans-serif;
}
.row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 12px 0;
}

textarea {
  width: 100%;
  min-height: 100px;
  padding: 8px;
  box-sizing: border-box;
}
button {
  padding: 8px 16px;
}
.meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  color: #666;
  font-size: 13px;
}

.error {
  color: #c00;
}

.output {
  background: #f6f6f6;
  padding: 12px;
  min-height: 140px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>