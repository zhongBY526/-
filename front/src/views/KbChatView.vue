<script setup>
import { ref, nextTick, onMounted } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { api } from '@/utils/api'
const md = new MarkdownIt()

const messages = ref([])   // { role, content, sources?, retryCount? }
const selectedKbIds = ref([])
const sending = ref(false)
const question = ref('')
const kbs = ref([])
const loadError = ref('')
const listRef = ref(null)
let controller = null
async function send(question) {
  messages.value.push({ role: 'user', content: question })
  // 先 push 一条空 assistant 消息，后续通过数组索引访问 reactive proxy 才能触发视图更新
  messages.value.push({ role: 'assistant', content: '', sources: [], retryCount: 0 })
  const replyIndex = messages.value.length - 1
  controller = new AbortController() // 每次发送新建，避免 stop() 后旧 signal 永久作废
  sending.value = true
  scrollBottom()
  try {
    const res = await fetch('/api/rag/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('token')}`, // api() 之外要手动带 token
      },
      body: JSON.stringify({ question, kbIds: selectedKbIds.value }),
      signal: controller.signal
    })
    if (res.status === 401) {
      localStorage.removeItem('token')
      throw new Error('未登录或登录已过期')
    }
    if (!res.ok) {
      // 400/401 走的是 JSON 而非 SSE，别丢掉后端 message
      let msg = `HTTP ${res.status}`
      try { const b = await res.json(); if (b?.message) msg = b.message } catch { }
      throw new Error(msg)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }) // 半个中文字符留到下一帧
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        let event = 'message'
        const dataLines = []
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
        }
        if (!dataLines.length) continue
        const data = JSON.parse(dataLines.join('\n'))
        if (event === 'sources') {
          messages.value[replyIndex].sources = data.sources
          messages.value[replyIndex].retryCount = data.retryCount
        } else if (event === 'token') {
          messages.value[replyIndex].content += data.delta ?? ''
          scrollBottom()
        } else if (event === 'error') {
          throw new Error(data.message ?? '回答生成失败')
        }
      }
    }
  } catch (e) {
    messages.value[replyIndex].content = messages.value[replyIndex].content || `出错了：${e.message}`
  } finally {
    sending.value = false
    scrollBottom()
  }
}
function stop() {
  controller?.abort()
}
function scoreLabel(type) {
  if (type === 'vector') return '向量相似度'
  if (type === 'rerank') return '重排分'
  return '融合分'   // RRF 分数量级 0.01x，只表名次，不当相关度展示
}
function render(text) {
  return DOMPurify.sanitize(md.render(text || ''))
}

async function submit() {
  const q = question.value.trim()
  if (!q || sending.value) return
  question.value = ''
  await send(q)
}

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function kbNames() {
  if (!selectedKbIds.value.length) return '全部可访问知识库'
  return kbs.value.filter(k => selectedKbIds.value.includes(k.id)).map(k => k.name).join('、')
}
onMounted(async () => {
  try { kbs.value = await api('/api/kb/list') } catch (e) { loadError.value = e.message }
})
</script>
<template>
  <div class="chat-page">
    <!-- 顶部：检索范围选择 -->
    <div class="chat-toolbar">
      <h3>知识库问答</h3>
      <details class="kb-picker">
        <summary>检索范围：{{ kbNames() }}</summary>
        <div class="kb-options">
          <p class="kb-hint">不勾选 = 检索全部可访问知识库</p>
          <label v-for="kb in kbs" :key="kb.id">
            <input type="checkbox" :value="kb.id" v-model="selectedKbIds" />
            {{ kb.name }}
          </label>
          <p v-if="loadError" class="kb-hint error">{{ loadError }}</p>
          <p v-if="!kbs.length && !loadError" class="kb-hint">
            暂无知识库，<router-link to="/kb">去创建</router-link>
          </p>
        </div>
      </details>
    </div>

    <!-- 消息列表 -->
    <div class="msg-list" ref="listRef">
      <p v-if="!messages.length" class="msg-empty">
        输入问题，基于知识库文档回答（附引用来源）
      </p>
      <div v-for="(msg, i) in messages" :key="i" class="msg-row" :class="msg.role">
        <div class="msg-bubble">
          <!-- 助手消息渲染 Markdown（已 sanitize），用户消息纯文本 -->
          <div v-if="msg.role === 'assistant'" class="md-body" v-html="render(msg.content)"></div>
          <template v-else>{{ msg.content }}</template>

          <div v-if="msg.retryCount > 0" class="retry-tip">经过 {{ msg.retryCount }} 次检索重写</div>

          <details v-if="msg.sources && msg.sources.length" class="sources">
            <summary>引用来源（{{ msg.sources.length }}）</summary>
            <div v-for="s in msg.sources" :key="s.index" class="source-item">
              <div class="source-head">
                <span>[{{ s.index }}] {{ s.file_name }} · 段落 {{ Number(s.chunk_index ?? 0) + 1 }}</span>
                <span class="source-score">{{ scoreLabel(s.score_type) }} {{ Number(s.score).toFixed(3) }}</span>
              </div>
              <p class="source-text">{{ s.text }}</p>
            </div>
          </details>
        </div>
      </div>
      <div v-if="sending" class="msg-row assistant">
        <div class="msg-bubble thinking">检索并思考中...</div>
      </div>
    </div>

    <!-- 输入区 -->
    <form class="input-bar" @submit.prevent="submit">
      <textarea
        v-model="question"
        rows="2"
        placeholder="输入问题，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="submit"
      ></textarea>
      <button type="submit" :disabled="sending || !question.trim()">
        {{ sending ? '回答中...' : '发送' }}
      </button>
      <button type="button" @click="stop" :disabled="!sending">停止</button>
    </form>
  </div>
</template>

<style scoped>
.chat-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 90px);
  box-sizing: border-box;
}

.chat-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.chat-toolbar h3 {
  margin: 0;
}

.kb-picker {
  position: relative;
  font-size: 14px;
}
.kb-picker summary {
  cursor: pointer;
  padding: 6px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  background: #fff;
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-options {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  z-index: 10;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  padding: 10px 14px;
  min-width: 240px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kb-options label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.kb-hint {
  margin: 0;
  font-size: 12px;
  color: #9ca3af;
}
.kb-hint.error {
  color: #b91c1c;
}
.kb-hint a {
  color: #2563eb;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px;
}
.msg-empty {
  color: #9ca3af;
  text-align: center;
  margin-top: 60px;
}

.msg-row {
  display: flex;
}
.msg-row.user {
  justify-content: flex-end;
}
.msg-row.assistant {
  justify-content: flex-start;
}
.msg-bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-row.user .msg-bubble {
  background: #2563eb;
  color: #fff;
}
.msg-row.assistant .msg-bubble {
  background: #fff;
  border: 1px solid #e5e7eb;
}
.msg-bubble.thinking {
  color: #9ca3af;
}

.md-body {
  white-space: normal;
}
.md-body :deep(p) {
  margin: 0 0 8px;
}
.md-body :deep(p:last-child) {
  margin-bottom: 0;
}
.md-body :deep(pre) {
  background: #f3f4f6;
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
}
.md-body :deep(code) {
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 13px;
}
.md-body :deep(table) {
  border-collapse: collapse;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 4px 8px;
}

.retry-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #d97706;
}

.sources {
  margin-top: 8px;
  border-top: 1px dashed #e5e7eb;
  padding-top: 6px;
  font-size: 13px;
}
.sources summary {
  cursor: pointer;
  color: #2563eb;
}
.source-item {
  margin-top: 8px;
  padding: 8px 10px;
  background: #f9fafb;
  border-radius: 6px;
}
.source-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-weight: 600;
}
.source-score {
  color: #6b7280;
  font-weight: 400;
  white-space: nowrap;
}
.source-text {
  margin: 6px 0 0;
  color: #6b7280;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
}

.input-bar {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.input-bar textarea {
  flex: 1;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  resize: none;
  box-sizing: border-box;
}
.input-bar button {
  padding: 0 24px;
  border: none;
  border-radius: 6px;
  background: #2563eb;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}
.input-bar button:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}
</style>