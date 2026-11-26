<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from '@/utils/api'

// ------- 状态 -------
const kbList = ref([])
const selectedKbIds = ref([])
const question = ref('')
const submitting = ref(false)
const tasks = ref([])
const current = ref(null)          // 当前选中任务详情
const errorMsg = ref('')
const confirmVisible = ref(false)  // 人工确认弹窗
const confirmComment = ref('')
const confirmBusy = ref(false)
let pollTimer = null

const STATUS_LABEL = {
  RUNNING: '执行中', PENDING_CONFIRM: '待人工确认', CONFIRMED: '已确认',
  REJECTED: '已驳回', EXPORTED: '已导出', FAILED: '失败',
}

// 流程可视化：按意图展示节点链，steps 里出现过的节点高亮
const PIPELINES = {
  QA: ['classify', 'retrieve', 'generate', 'formatCheck'],
  DRAFT: ['classify', 'retrieve', 'generate', 'formatCheck'],
  STATS: ['classify', 'stats'],
  CHAT: ['classify', 'chat'],
}
const NODE_LABEL = {
  classify: '意图识别', retrieve: '知识库检索', generate: '生成',
  formatCheck: '格式校验 Skill', stats: '统计工具(DB)', chat: '时间工具(HTTP)',
}

const pipeline = computed(() => {
  if (!current.value) return []
  const intent = current.value.intent || 'QA'
  return PIPELINES[intent] || PIPELINES.QA
})
const steps = computed(() => parseJson(current.value?.steps, []))
const sources = computed(() => parseJson(current.value?.sources, []))
const formatReport = computed(() => parseJson(current.value?.formatReport, null))
// 格式校验报告统计：ERROR 为阻断级硬伤（passed=false 的唯一依据），WARN 为瑕疵（只扣分不阻断）
const reportStats = computed(() => {
  const items = formatReport.value?.items || []
  return {
    errors: items.filter(i => !i.passed && i.severity === 'ERROR').length,
    warns: items.filter(i => !i.passed && i.severity === 'WARN').length,
  }
})

function stepOf(node) {
  return steps.value.find(s => s.node === node)
}
function parseJson(text, fallback) {
  if (!text) return fallback
  try { return JSON.parse(text) } catch { return fallback }
}

// ------- 数据加载 -------
async function loadKbs() {
  kbList.value = await api('/api/kb/list')
}
async function loadTasks() {
  tasks.value = await api('/api/workflow/tasks')
}
async function selectTask(id) {
  errorMsg.value = ''
  current.value = await api(`/api/workflow/tasks/${id}`)
  schedulePolling()
}

// RUNNING 任务 2s 轮询直到出结果
function schedulePolling() {
  clearTimeout(pollTimer)
  if (current.value?.status === 'RUNNING') {
    pollTimer = setTimeout(async () => {
      try {
        current.value = await api(`/api/workflow/tasks/${current.value.id}`)
        await loadTasks()
      } catch (e) { errorMsg.value = e.message }
      schedulePolling()
    }, 2000)
  }
}

// ------- 操作 -------
async function submit() {
  if (!question.value.trim()) return
  submitting.value = true
  errorMsg.value = ''
  try {
    const task = await api('/api/workflow/tasks', {
      method: 'POST',
      body: JSON.stringify({ question: question.value.trim(), kbIds: selectedKbIds.value }),
    })
    question.value = ''
    await loadTasks()
    await selectTask(task.id)
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}

function openConfirm() {
  confirmComment.value = ''
  confirmVisible.value = true
}
async function doConfirm(action) {
  confirmBusy.value = true
  errorMsg.value = ''
  try {
    current.value = await api(`/api/workflow/tasks/${current.value.id}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ action, comment: confirmComment.value }),
    })
    confirmVisible.value = false
    await loadTasks()
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    confirmBusy.value = false
  }
}

async function retry() {
  errorMsg.value = ''
  try {
    current.value = await api(`/api/workflow/tasks/${current.value.id}/retry`, { method: 'POST' })
    await loadTasks()
    schedulePolling()
  } catch (e) { errorMsg.value = e.message }
}

// 导出接口返回文件而非 JSON，不能用 api() 封装
async function exportTask() {
  errorMsg.value = ''
  try {
    const res = await fetch(`/api/workflow/tasks/${current.value.id}/export`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('token')}` },
    })
    if (!res.ok) {
      const json = await res.json().catch(() => ({}))
      throw new Error(json.message || `导出失败(${res.status})`)
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `workflow-task-${current.value.id}.md`
    a.click()
    URL.revokeObjectURL(url)
    current.value = await api(`/api/workflow/tasks/${current.value.id}`)
    await loadTasks()
  } catch (e) { errorMsg.value = e.message }
}

onMounted(async () => {
  try {
    await Promise.all([loadKbs(), loadTasks()])
    if (tasks.value.length) await selectTask(tasks.value[0].id)
  } catch (e) { errorMsg.value = e.message }
})
onUnmounted(() => clearTimeout(pollTimer))
</script>

<template>
  <div class="wf-page">
    <!-- 左栏：新建任务 + 任务列表 -->
    <aside class="wf-left">
      <h3>新建工作流任务</h3>
      <textarea v-model="question" rows="4"
        placeholder="例如：起草一份关于开展公文格式自查的通知 / 医疗器械经营许可证有效期是多久？ / 统计知识库入库情况"></textarea>
      <div class="kb-picker">
        <label v-for="kb in kbList" :key="kb.id" class="kb-item">
          <input type="checkbox" :value="kb.id" v-model="selectedKbIds" />
          {{ kb.name }}
        </label>
        <span v-if="!kbList.length" class="muted">暂无知识库，请先到「知识库管理」创建</span>
        <span v-else-if="!selectedKbIds.length" class="muted">不勾选 = 检索全部可访问知识库</span>
      </div>
      <button class="btn-primary" :disabled="submitting || !question.trim()" @click="submit">
        {{ submitting ? '提交中…' : '触发工作流' }}
      </button>

      <h3>任务列表</h3>
      <div class="task-list">
        <div v-for="t in tasks" :key="t.id" class="task-item"
             :class="{ active: current?.id === t.id }" @click="selectTask(t.id)">
          <div class="task-q">#{{ t.id }} {{ t.question }}</div>
          <span class="badge" :class="'st-' + t.status">{{ STATUS_LABEL[t.status] || t.status }}</span>
        </div>
        <p v-if="!tasks.length" class="muted">还没有任务</p>
      </div>
    </aside>

    <!-- 右栏：任务详情 -->
    <main class="wf-right">
      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
      <template v-if="current">
        <div class="detail-head">
          <h2>任务 #{{ current.id }}</h2>
          <span class="badge" :class="'st-' + current.status">{{ STATUS_LABEL[current.status] }}</span>
          <span v-if="current.intent" class="intent-tag">意图：{{ current.intent }}</span>
        </div>
        <p class="task-question">{{ current.question }}</p>

        <!-- 流程可视化 -->
        <div class="pipeline">
          <template v-for="(node, i) in pipeline" :key="node">
            <div class="pipe-node" :class="stepOf(node) ? 'done-' + stepOf(node).status : 'pending'">
              <div class="pipe-name">{{ NODE_LABEL[node] }}</div>
              <div class="pipe-detail">{{ stepOf(node)?.detail || '等待执行' }}</div>
            </div>
            <span v-if="i < pipeline.length - 1" class="pipe-arrow">→</span>
          </template>
          <span class="pipe-arrow">→</span>
          <div class="pipe-node" :class="['PENDING_CONFIRM','CONFIRMED','EXPORTED'].includes(current.status) ? 'done-ok' : 'pending'">
            <div class="pipe-name">人工确认</div>
            <div class="pipe-detail">{{ STATUS_LABEL[current.status] }}</div>
          </div>
          <span class="pipe-arrow">→</span>
          <div class="pipe-node" :class="current.status === 'EXPORTED' ? 'done-ok' : 'pending'">
            <div class="pipe-name">导出</div>
            <div class="pipe-detail">{{ current.status === 'EXPORTED' ? '已导出' : '等待导出' }}</div>
          </div>
        </div>

        <p v-if="current.status === 'RUNNING'" class="muted">⏳ 工作流执行中，页面每 2 秒自动刷新…</p>
        <p v-if="current.errorMsg" class="error">异常：{{ current.errorMsg }}</p>

        <!-- 草稿/回答 -->
        <section v-if="current.draft" class="panel">
          <h3>{{ current.intent === 'DRAFT' ? '公文草稿' : '结果' }}</h3>
          <pre class="draft">{{ current.draft }}</pre>
        </section>

        <!-- 格式校验报告：只有带 items 才是真实报告；CHAT/STATS 的 {} 与 QA 的 skipped 都不渲染 -->
        <section v-if="formatReport?.items?.length" class="panel">
          <h3>格式校验报告（Skill）
            <span class="score" :class="reportStats.errors ? 'score-bad' : 'score-ok'">
              {{ formatReport.score }} 分 ·
              {{ reportStats.errors ? reportStats.errors + ' 处硬伤' : '无硬伤' }}
              <template v-if="reportStats.warns"> · {{ reportStats.warns }} 处瑕疵</template>
            </span>
          </h3>
          <ul class="rule-list">
            <li v-for="item in formatReport.items" :key="item.rule"
                :class="item.passed ? 'rule-ok' : (item.severity === 'ERROR' ? 'rule-error' : 'rule-warn')">
              <b>{{ item.rule }} {{ item.name }}</b>
              <span class="rule-sev">{{ item.passed ? '✓' : item.severity }}</span>
              {{ item.message }}
            </li>
          </ul>
        </section>

        <!-- 引用来源 -->
        <section v-if="sources.length" class="panel">
          <h3>引用来源</h3>
          <div v-for="s in sources" :key="s.index" class="source-item">
            [{{ s.index }}] {{ s.file_name }} · 段落 {{ Number(s.chunk_index ?? 0) + 1 }}
          </div>
        </section>

        <!-- 操作区 -->
        <div class="actions">
          <button v-if="current.status === 'PENDING_CONFIRM'" class="btn-primary" @click="openConfirm">人工确认…</button>
          <button v-if="['CONFIRMED','EXPORTED'].includes(current.status)" class="btn-primary" @click="exportTask">
            {{ current.status === 'EXPORTED' ? '重新下载导出文件' : '导出 Markdown' }}
          </button>
          <button v-if="['REJECTED','FAILED'].includes(current.status)" class="btn-warn" @click="retry">重跑任务</button>
          <span v-if="current.confirmComment" class="muted">确认备注：{{ current.confirmComment }}</span>
        </div>
      </template>
      <p v-else class="muted">左侧提交一个任务，或选择历史任务查看详情</p>
    </main>

    <!-- 人工确认弹窗 -->
    <div v-if="confirmVisible" class="modal-mask" @click.self="confirmVisible = false">
      <div class="modal">
        <h3>人工确认 · 任务 #{{ current.id }}</h3>
        <p class="muted">请核对上方草稿与格式校验报告后决定。驳回后可修改要求重新提交，或原样重跑。</p>
        <textarea v-model="confirmComment" rows="3" placeholder="确认/驳回备注（可选）"></textarea>
        <div class="modal-actions">
          <button class="btn-primary" :disabled="confirmBusy" @click="doConfirm('CONFIRM')">通过</button>
          <button class="btn-danger" :disabled="confirmBusy" @click="doConfirm('REJECT')">驳回</button>
          <button class="btn-plain" :disabled="confirmBusy" @click="confirmVisible = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wf-page { display: flex; gap: 16px; padding: 16px; align-items: flex-start; }
.wf-left { width: 320px; flex-shrink: 0; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px; }
.wf-right { flex: 1; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; min-height: 400px; }
h3 { margin: 12px 0 8px; font-size: 15px; }
textarea { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 6px; padding: 8px; font-size: 14px; resize: vertical; }
.kb-picker { margin: 8px 0; display: flex; flex-direction: column; gap: 4px; max-height: 140px; overflow-y: auto; }
.kb-item { font-size: 13px; display: flex; gap: 6px; align-items: center; }
.muted { color: #9ca3af; font-size: 13px; }
.error { color: #dc2626; font-size: 14px; }
.btn-primary { padding: 8px 16px; background: #2563eb; color: #fff; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; }
.btn-primary:disabled { background: #93c5fd; cursor: not-allowed; }
.btn-warn { padding: 8px 16px; background: #f59e0b; color: #fff; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; }
.btn-danger { padding: 8px 16px; background: #dc2626; color: #fff; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; }
.btn-plain { padding: 8px 16px; background: #fff; border: 1px solid #d1d5db; border-radius: 6px; cursor: pointer; font-size: 14px; }
.task-list { display: flex; flex-direction: column; gap: 6px; max-height: 380px; overflow-y: auto; }
.task-item { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; cursor: pointer; }
.task-item.active { border-color: #2563eb; background: #eff6ff; }
.task-q { font-size: 13px; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; }
.st-RUNNING { background: #fef3c7; color: #92400e; }
.st-PENDING_CONFIRM { background: #dbeafe; color: #1e40af; }
.st-CONFIRMED { background: #d1fae5; color: #065f46; }
.st-REJECTED { background: #fee2e2; color: #991b1b; }
.st-EXPORTED { background: #e0e7ff; color: #3730a3; }
.st-FAILED { background: #fecaca; color: #7f1d1d; }
.detail-head { display: flex; align-items: center; gap: 10px; }
.detail-head h2 { margin: 0; font-size: 18px; }
.intent-tag { font-size: 12px; color: #6b7280; border: 1px solid #e5e7eb; padding: 2px 8px; border-radius: 10px; }
.task-question { color: #374151; font-size: 14px; }
.pipeline { display: flex; align-items: stretch; gap: 6px; flex-wrap: wrap; margin: 12px 0; }
.pipe-node { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px 10px; min-width: 110px; }
.pipe-name { font-size: 13px; font-weight: 600; }
.pipe-detail { font-size: 11px; color: #6b7280; margin-top: 2px; max-width: 160px; }
.pipe-node.pending { opacity: .55; }
.pipe-node.done-ok { border-color: #10b981; background: #ecfdf5; }
.pipe-node.done-error { border-color: #dc2626; background: #fef2f2; }
.pipe-node.done-fallback, .pipe-node.done-refused { border-color: #f59e0b; background: #fffbeb; }
.pipe-node.done-skipped { border-color: #9ca3af; background: #f9fafb; }
.pipe-arrow { align-self: center; color: #9ca3af; }
.panel { border-top: 1px solid #f3f4f6; margin-top: 14px; padding-top: 6px; }
.draft { white-space: pre-wrap; font-family: inherit; font-size: 14px; background: #f9fafb; border: 1px solid #f3f4f6; border-radius: 6px; padding: 12px; line-height: 1.7; }
.score { font-size: 13px; margin-left: 8px; padding: 2px 8px; border-radius: 10px; }
.score-ok { background: #d1fae5; color: #065f46; }
.score-bad { background: #fee2e2; color: #991b1b; }
.rule-list { list-style: none; padding: 0; margin: 6px 0; display: flex; flex-direction: column; gap: 4px; }
.rule-list li { font-size: 13px; padding: 6px 8px; border-radius: 4px; }
.rule-ok { background: #f0fdf4; color: #166534; }
.rule-error { background: #fef2f2; color: #991b1b; }
.rule-warn { background: #fffbeb; color: #92400e; }
.rule-sev { margin: 0 6px; font-weight: 600; }
.source-item { font-size: 13px; color: #4b5563; padding: 2px 0; }
.actions { margin-top: 16px; display: flex; gap: 10px; align-items: center; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { background: #fff; border-radius: 8px; padding: 20px; width: 440px; max-width: 92vw; }
.modal-actions { display: flex; gap: 10px; margin-top: 12px; }
@media (max-width: 860px) { .wf-page { flex-direction: column; } .wf-left { width: 100%; box-sizing: border-box; } }
</style>