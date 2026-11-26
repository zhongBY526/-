<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { api } from '@/utils/api'

const kbs = ref([])
const selectedKb = ref(null)
const docs = ref([])
const error = ref('')
const creating = ref(false)
const uploading = ref(false)
let pollTimer = null

// 新建库表单，visibility 与后端 KbRequest 校验规则一致
const kbForm = reactive({ name: '', description: '', visibility: 'PRIVATE' })
const visibilityLabels = {
  PRIVATE: '仅自己可见',
  DEPARTMENT: '本部门可见',
  PUBLIC: '全员可见',
}

async function loadKbs() { kbs.value = await api('/api/kb/list') }

async function createKb() {
  if (!kbForm.name.trim()) { error.value = '知识库名称不能为空'; return }
  error.value = ''
  creating.value = true
  try {
    await api('/api/kb', { method: 'POST', body: JSON.stringify({ ...kbForm }) })
    kbForm.name = ''
    kbForm.description = ''
    kbForm.visibility = 'PRIVATE'
    await loadKbs()
  } catch (e) {
    error.value = e.message
  } finally {
    creating.value = false
  }
}

async function selectKb(kb) {
  selectedKb.value = kb
  docs.value = []
  try {
    await loadDocs()
    startPolling()
  } catch (e) {
    error.value = e.message
  }
}

async function loadDocs() {
  docs.value = await api(`/api/kb/${selectedKb.value.id}/documents`)
}

// 有 PROCESSING 状态时每 2s 轮询，全部终态后停止；组件卸载必须 clearInterval
function startPolling() {
  clearInterval(pollTimer)
  if (!docs.value.some(d => d.status === 'PROCESSING')) return
  pollTimer = setInterval(async () => {
    try {
      await loadDocs()
      if (!docs.value.some(d => d.status === 'PROCESSING')) clearInterval(pollTimer)
    } catch (e) {
      // 轮询失败即停，避免网络异常时控制台刷未处理异常
      clearInterval(pollTimer)
      error.value = e.message
    }
  }, 2000)
}
onUnmounted(() => clearInterval(pollTimer))

async function upload(event) {
  const file = event.target.files[0]
  event.target.value = '' // 允许重复选择同一文件
  if (!file || !selectedKb.value) return
  error.value = ''
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    await api(`/api/kb/${selectedKb.value.id}/documents`, { method: 'POST', body: form })
    await loadDocs()
    startPolling()
  } catch (e) {
    error.value = e.message
  } finally {
    uploading.value = false
  }
}

async function removeDoc(doc) {
  if (!confirm(`确定删除文档「${doc.fileName}」吗？删除后其向量数据不可恢复。`)) return
  error.value = ''
  try {
    await api(`/api/kb/documents/${doc.id}`, { method: 'DELETE' })
    await loadDocs()
  } catch (e) {
    error.value = e.message
  }
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const statusLabels = { PROCESSING: '处理中', READY: '就绪', FAILED: '失败' }

onMounted(async () => {
  try { await loadKbs() } catch (e) { error.value = e.message }
})
</script>

<template>
  <div class="kb-page">
    <p v-if="error" class="error-banner">
      {{ error }}
      <button class="error-close" @click="error = ''">×</button>
    </p>

    <div class="kb-layout">
      <!-- 左栏：新建库表单 + 库列表 -->
      <aside class="kb-sidebar">
        <form class="kb-create" @submit.prevent="createKb">
          <h3>新建知识库</h3>
          <input v-model="kbForm.name" maxlength="128" placeholder="名称（必填）" />
          <textarea v-model="kbForm.description" maxlength="512" rows="2" placeholder="描述（可选）"></textarea>
          <select v-model="kbForm.visibility">
            <option value="PRIVATE">PRIVATE — 仅自己可见</option>
            <option value="DEPARTMENT">DEPARTMENT — 本部门可见</option>
            <option value="PUBLIC">PUBLIC — 全员可见</option>
          </select>
          <button type="submit" :disabled="creating">{{ creating ? '创建中...' : '创建' }}</button>
        </form>

        <ul class="kb-list">
          <li
            v-for="kb in kbs"
            :key="kb.id"
            :class="{ active: selectedKb && selectedKb.id === kb.id }"
            @click="selectKb(kb)"
          >
            <div class="kb-item-head">
              <span class="kb-name">{{ kb.name }}</span>
              <span class="kb-visibility">{{ visibilityLabels[kb.visibility] || kb.visibility }}</span>
            </div>
            <p v-if="kb.description" class="kb-desc">{{ kb.description }}</p>
          </li>
          <li v-if="!kbs.length" class="kb-empty">暂无知识库，先创建一个吧</li>
        </ul>
      </aside>

      <!-- 右栏：所选库的文档管理 -->
      <section class="doc-panel">
        <template v-if="selectedKb">
          <div class="doc-header">
            <h3>{{ selectedKb.name }}</h3>
            <label class="upload-btn" :class="{ disabled: uploading }">
              {{ uploading ? '上传中...' : '上传文档' }}
              <input
                type="file"
                accept=".pdf,.doc,.docx,.md,.txt"
                :disabled="uploading"
                @change="upload"
              />
            </label>
          </div>

          <table v-if="docs.length" class="doc-table">
            <thead>
              <tr>
                <th>文件名</th>
                <th>类型</th>
                <th>大小</th>
                <th>状态</th>
                <th>分块数</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="doc in docs" :key="doc.id">
                <td class="doc-name">{{ doc.fileName }}</td>
                <td>{{ doc.fileType }}</td>
                <td>{{ formatSize(doc.fileSize) }}</td>
                <td>
                  <span
                    class="badge"
                    :class="doc.status.toLowerCase()"
                    :title="doc.status === 'FAILED' ? doc.errorMsg : ''"
                  >{{ statusLabels[doc.status] || doc.status }}</span>
                </td>
                <td>{{ doc.chunkCount }}</td>
                <td><button class="doc-delete" @click="removeDoc(doc)">删除</button></td>
              </tr>
            </tbody>
          </table>
          <p v-else class="doc-empty">该库还没有文档，点击「上传文档」添加（支持 pdf / doc / docx / md / txt）</p>
        </template>
        <p v-else class="doc-empty">请先在左侧选择一个知识库</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.kb-page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 16px;
}

.error-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fef2f2;
  color: #b91c1c;
  border: 1px solid #fecaca;
  border-radius: 4px;
  padding: 8px 12px;
  margin: 0 0 12px;
}
.error-close {
  border: none;
  background: none;
  color: #b91c1c;
  font-size: 16px;
  cursor: pointer;
}

.kb-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

/* 左栏 */
.kb-sidebar {
  width: 300px;
  flex-shrink: 0;
}
.kb-create {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
}
.kb-create h3 {
  margin: 0 0 4px;
  font-size: 15px;
}
.kb-create input,
.kb-create textarea,
.kb-create select {
  padding: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
  box-sizing: border-box;
  width: 100%;
}
.kb-create button {
  padding: 8px;
  border: none;
  border-radius: 4px;
  background: #2563eb;
  color: #fff;
  cursor: pointer;
}
.kb-create button:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}

.kb-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.kb-list li {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
}
.kb-list li.active {
  border-color: #2563eb;
  box-shadow: 0 0 0 1px #2563eb;
}
.kb-list li.kb-empty {
  color: #9ca3af;
  cursor: default;
  text-align: center;
}
.kb-item-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.kb-name {
  font-weight: 600;
}
.kb-visibility {
  font-size: 12px;
  color: #6b7280;
  white-space: nowrap;
}
.kb-desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右栏 */
.doc-panel {
  flex: 1;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 12px 16px;
  min-height: 300px;
}
.doc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.doc-header h3 {
  margin: 0;
}
.upload-btn {
  padding: 8px 16px;
  border-radius: 4px;
  background: #2563eb;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}
.upload-btn.disabled {
  background: #93c5fd;
  cursor: not-allowed;
}
.upload-btn input {
  display: none;
}

.doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}
.doc-table th,
.doc-table td {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid #f3f4f6;
}
.doc-table th {
  color: #6b7280;
  font-weight: 500;
  background: #f9fafb;
}
.doc-name {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
}
.badge.processing {
  background: #dbeafe;
  color: #1d4ed8;
}
.badge.ready {
  background: #dcfce7;
  color: #15803d;
}
.badge.failed {
  background: #fee2e2;
  color: #b91c1c;
  cursor: help;
}

.doc-delete {
  border: 1px solid #fecaca;
  background: #fff;
  color: #b91c1c;
  border-radius: 4px;
  padding: 4px 10px;
  cursor: pointer;
}
.doc-delete:hover {
  background: #fef2f2;
}

.doc-empty {
  color: #9ca3af;
  text-align: center;
  padding: 40px 0;
}

@media (max-width: 768px) {
  .kb-layout {
    flex-direction: column;
  }
  .kb-sidebar {
    width: 100%;
  }
}
</style>