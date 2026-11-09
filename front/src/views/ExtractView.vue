<script setup>
import { ref } from 'vue'

const text = ref('')
const loading = ref(false)
const error = ref('')
const result = ref(null)

const sample = `关于做好2026年秋季心理健康排查工作的通知
各学院：
请各学院于11月10日前完成排查。
学生工作处
2026年11月1日`

function fillSample() {
  text.value = sample
}
async function extract() {
  if (!text.value.trim() || loading.value) return

  loading.value = true
  error.value = ''
  result.value = null

  try {
    const response = await fetch('/api/extract', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: text.value })
    })

    const body = await response.json()

    if (!response.ok || body.code !== 0) {
      error.value = body.message || `HTTP ${response.status}`
      return
    }

    result.value = body.data
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <h1>公文要素抽取</h1>

    <textarea v-model="text" placeholder="粘贴公文文本..."></textarea>

    <div class="row">
      <button :disabled="loading || !text.trim()" @click="extract">
        {{ loading ? '抽取中...' : '抽取' }}
      </button>
      <button :disabled="loading" @click="fillSample">填充示例</button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="result" class="result">
      <div class="field"><span>文种</span><b>{{ result.docType || '-' }}</b></div>
      <div class="field"><span>标题</span><b>{{ result.title || '-' }}</b></div>
      <div class="field"><span>主送单位</span><b>{{ result.addressee || '-' }}</b></div>
      <div class="field"><span>落款单位</span><b>{{ result.signature || '-' }}</b></div>
      <div class="field"><span>成文日期</span><b>{{ result.date || '-' }}</b></div>
      <div v-if="result.warnings && result.warnings.length" class="warnings">
        <p v-for="(w, i) in result.warnings" :key="i">{{ w }}</p>
      </div>
      <p v-else class="ok">无警告</p>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 820px;
  margin: 40px auto;
  padding: 0 16px;
  font-family: system-ui, sans-serif;
}

textarea {
  width: 100%;
  min-height: 140px;
  padding: 8px;
  box-sizing: border-box;
}

.row {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}

button {
  padding: 8px 16px;
}

.error {
  color: #c00;
}

.result {
  background: #f6f6f6;
  padding: 16px;
  border-radius: 6px;
}

.field {
  display: flex;
  gap: 12px;
  margin: 8px 0;
}

.field span {
  width: 90px;
  color: #666;
}

.warnings {
  margin-top: 12px;
  color: #9a6700;
  background: #fff8e1;
  padding: 8px 12px;
  border-radius: 4px;
}

.warnings p {
  margin: 4px 0;
}

.ok {
  color: #2e7d32;
}
</style>