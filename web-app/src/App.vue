<script setup>
import { computed, onMounted, ref } from 'vue'

const session = ref(JSON.parse(sessionStorage.getItem('warehouse-session') || 'null'))
const username = ref('operator'), password = ref('demo-only')
const inventory = ref([]), runs = ref([]), audit = ref([]), readiness = ref({})
const scenario = ref('normal'), mode = ref('NORMAL'), parentRunId = ref(''), reason = ref('例行核对演示库存')
const preview = ref(null), confirmed = ref(false), busy = ref(false), error = ref(''), notice = ref('')
const message = ref('查询库存'), messages = ref([]), tab = ref('workflow')
const operator = computed(() => session.value?.role === 'operator')
const scenarios = { normal: '正常导出', empty: '空导出', stale: '过期数据', duplicate: '重复 SKU', spike: '总量异常', unavailable: '导出失败' }
const statuses = { RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败，需核对' }

async function api(path, body) {
  const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', headers: {
    ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    ...(session.value ? { Authorization: `Bearer ${session.value.token}` } : {})
  }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const result = await response.json()
  if (!response.ok) {
    if (response.status === 401 && session.value) logout()
    throw new Error(`${result.message || '请求失败'}${result.code ? ' · ' + result.code : ''}`)
  }
  return result
}
async function work(action) {
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function login() { await work(async () => {
  session.value = await api('/api/auth/login', { username: username.value, password: password.value })
  sessionStorage.setItem('warehouse-session', JSON.stringify(session.value)); await refresh()
}) }
function logout() { session.value = null; sessionStorage.removeItem('warehouse-session'); preview.value = null; messages.value = [] }
async function refresh() {
  const results = await Promise.all(['/api/inventory', '/api/runs', '/api/audit', '/api/readiness'].map(p => api(p)))
  ;[inventory.value, runs.value, audit.value, readiness.value] = results
}
function invalidate() { preview.value = null; confirmed.value = false }
async function inspect() { await work(async () => {
  preview.value = await api('/api/sync/preview', { scenario: scenario.value }); confirmed.value = false
}) }
async function execute() { await work(async () => {
  const result = await api('/api/sync/execute', { previewToken: preview.value.previewToken, mode: mode.value,
    parentRunId: mode.value === 'NORMAL' ? null : parentRunId.value, reason: reason.value, confirmed: confirmed.value })
  notice.value = `任务${statuses[result.status]}。${result.errorMessage || '业务快照、结果与审计已提交。'}`
  invalidate(); await refresh()
}) }
function retry(run, nextMode) {
  mode.value = nextMode; parentRunId.value = run.runId; scenario.value = 'normal'
  reason.value = nextMode === 'RETRY' ? '核对拒写原因后，重新取得正常导出' : '人工确认需要重新核对库存'
  tab.value = 'workflow'; invalidate(); window.scrollTo({ top: 0, behavior: 'smooth' })
}
async function ask() { if (!message.value.trim()) return; await work(async () => {
  const text = message.value.trim(); messages.value.push({ role: 'user', text }); message.value = ''
  const result = await api('/api/chat', { message: text }); messages.value.push({ role: 'assistant', ...result })
}) }
async function download() { await work(async () => {
  const response = await fetch('/api/reconciliation.csv', { headers: { Authorization: `Bearer ${session.value.token}` } })
  if (!response.ok) throw new Error('核对表导出失败')
  const url = URL.createObjectURL(await response.blob()); const link = document.createElement('a')
  link.href = url; link.download = 'synthetic-stock-reconciliation.csv'; link.click(); URL.revokeObjectURL(url)
}) }
onMounted(() => { if (session.value) work(refresh) })
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 7 9-4 9 4v10l-9 4-9-4V7Z"/><path d="m3 7 9 4 9-4M12 11v10M7.5 5l9 4"/></svg></span><div>库管作业台<span class="brand-sub">WAREHOUSE DESK</span></div></div><div class="workspace-label"><span class="workspace-avatar">W</span><div>库存作业空间<small>本机演示环境</small></div><span class="workspace-chevron">⌄</span></div>
      <div class="nav-label">工作台</div>
      <button :class="['nav', { active: tab === 'workflow' }]" @click="tab = 'workflow'"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg><span>库存同步与核对</span><span class="nav-index">01</span></button>
      <button :class="['nav', { active: tab === 'assistant' }]" @click="tab = 'assistant'"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2V11.5A8.5 8.5 0 0 1 10.5 3h2a8.5 8.5 0 0 1 8.5 8.5Z"/><path d="M7 10h9M7 14h6"/></svg><span>只读查询助手</span><span class="nav-index">02</span></button>
      <div class="sidebar-note"><span class="dot"></span> 独立公开演示<p>所有商品、数据与场景均为虚构。<br>不连接公司系统或真实飞书。</p><span class="sidebar-version">Workflow Rebuild · v1.0</span></div>
    </aside>
    <main>
      <header class="topbar"><div class="breadcrumb"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z"/></svg><span>作业空间</span><span class="crumb-divider">/</span><b>{{ tab === 'workflow' ? '库存同步与核对' : '只读查询助手' }}</b></div><div v-if="session" class="user"><span class="user-avatar">{{ operator ? 'OP' : 'VI' }}</span><div><strong>{{ session.username }}</strong><small>{{ operator ? '操作员' : '只读观察员' }}</small></div><button class="text-button logout" @click="logout">退出</button></div><span v-else class="environment"><span class="dot"></span> 本地沙箱</span></header>
      <div class="page-content"><div class="page-heading"><div><span class="eyebrow">WAREHOUSE / OPERATIONS</span><h1>{{ tab === 'workflow' ? '库存同步与核对' : '只读查询助手' }}</h1><p>{{ tab === 'workflow' ? '核对来源、预检变更，让每次库存更新有据可查。' : '从业务表读取信息，让每个查询结论都有依据。' }}</p></div><span class="environment"><span class="dot"></span> 合成数据演示</span></div>
      <div v-if="error" class="alert error" role="alert">{{ error }}</div>
      <div v-if="notice" class="alert success" role="status">{{ notice }}</div>
      <section v-if="!session" class="card login"><div class="login-story"><div class="story-label"><span class="dot"></span> WORKFLOW REBUILD</div><div class="story-content"><span class="story-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 7 9-4 9 4v10l-9 4-9-4V7Z"/><path d="m3 7 9 4 9-4M12 11v10"/></svg></span><h2>每次更新，<br>都有一条可信的路径。</h2><p>从数据来源到库存业务表，<br>保留校验、确认与恢复的完整记录。</p><div class="login-flow"><span>来源校验</span><i>→</i><span>人工确认</span><i>→</i><span>结果追溯</span></div></div><small>独立重设计的实习经验演示 · 全部使用合成数据</small></div><div class="login-form"><span class="eyebrow">WELCOME TO WORKSPACE</span><h2>进入本地演示</h2><p>选择你的演示身份，开始一次库存核对。</p><form @submit.prevent="login"><label>演示身份<select v-model="username"><option>operator</option><option>viewer</option></select></label><label>演示口令<input v-model="password" type="password" autocomplete="current-password"></label><div class="identity-note">operator 可执行核对；viewer 仅可查询。</div><button :disabled="busy">进入工作台 <span aria-hidden="true">→</span></button></form><small>本地公开样例口令：<code>demo-only</code><br>演示配置仅供本机运行。</small></div></section>
      <template v-else>
        <div class="metrics"><div class="metric date-metric"><span>演示业务日</span><strong>{{ readiness.businessDate || '—' }}</strong><small>固定业务时钟，便于重复演示</small></div>
          <div class="metric sku-metric"><span>在册商品</span><strong>{{ inventory.length }}<em>个 SKU</em></strong><small>来自当前库存业务表</small></div><div class="metric readiness-metric"><span>库存数据准备</span><strong :class="readiness.ready ? 'green' : 'amber'">{{ readiness.ready ? '日期已匹配' : '需要更新' }}</strong><small>按每个 SKU 的数据日期判断</small></div>
          <div class="metric run-metric"><span>最近任务</span><strong>{{ statuses[readiness.lastAttempt?.status] || '尚未运行' }}</strong><small>失败不覆盖上次成功的数据事实</small></div></div>
        <template v-if="tab === 'workflow'">
          <div class="workflow-grid">
          <section class="card inventory-card"><div class="section-title"><div><span class="eyebrow">INVENTORY OVERVIEW</span><h2>库存核对</h2></div><button class="secondary" :disabled="busy" @click="download">导出核对 CSV</button></div>
            <div class="table-wrap"><table><thead><tr><th>商品 / SKU</th><th>库存</th><th>每箱</th><th>数据日期</th><th>来源 / 时效</th></tr></thead>
              <tbody><tr v-for="item in inventory" :key="item.sku"><td><b>{{ item.label }}</b><small>{{ item.sku }}</small></td><td class="number">{{ item.quantity }}</td><td>{{ item.packSize }} 件</td><td>{{ item.dataDate }}</td><td><span :class="['badge', item.fresh ? 'ok' : 'warn']">{{ item.fresh ? '日期匹配' : '旧数据' }}</span><small>{{ item.source }}</small></td></tr></tbody></table></div>
            <div class="card-note"><span class="info-icon" aria-hidden="true">i</span><p>这里只证明库存数据准备情况，不表示订单已发货、已到货或可以承诺交期。</p></div><div class="process-guide"><span class="guide-label">更新路径</span><div><span><b>1</b> 选择导出来源</span><i>→</i><span><b>2</b> 预检并确认</span><i>→</i><span><b>3</b> 留存结果</span></div></div>
          </section>
          <section class="card operation-card"><div class="section-title"><div><span class="eyebrow">HUMAN-IN-THE-LOOP</span><h2>预检变更，再确认执行</h2></div><span class="badge neutral">人工操作</span></div>
            <p v-if="!operator">viewer 可以查询数据与任务；请使用 operator 身份体验预检与确认。</p>
            <form v-else @submit.prevent="inspect">
              <div class="form-grid"><label>导出场景<select v-model="scenario" @change="invalidate"><option v-for="(label, key) in scenarios" :key="key" :value="key">{{ label }}</option></select></label>
                <label>执行模式<select v-model="mode" @change="invalidate"><option value="NORMAL">普通同步 · 当日唯一</option><option value="RETRY">失败重试 · 追加记录</option><option value="REFRESH">人工刷新 · 追加记录</option></select></label>
                <label v-if="mode !== 'NORMAL'">来源任务<select v-model="parentRunId" required @change="invalidate"><option value="" disabled>请选择来源任务</option><option v-for="run in runs.filter(r => mode !== 'RETRY' || r.status === 'FAILED')" :key="run.runId" :value="run.runId">{{ statuses[run.status] }} / {{ run.runId.slice(0, 8) }}</option></select></label>
                <label class="wide">执行原因<input v-model="reason" maxlength="240" required placeholder="说明为什么需要这次操作"></label></div>
              <button class="inspect-button" :disabled="busy || (mode !== 'NORMAL' && !parentRunId)">预检来源与变更</button><p class="form-helper">预检不会写入库存，模型没有执行权限。</p>
            </form>
            <div v-if="preview" :class="['preview', { 'preview-warning': !preview.eligible }]"><span :class="['badge', preview.eligible ? 'ok' : 'warn']">{{ preview.eligible ? '等待人工确认' : '拒写保护已生效' }}</span>
              <h3>{{ preview.eligible ? '预检通过，等待你确认' : '发现问题：业务表将保持原状' }}</h3>
              <ul v-if="!preview.eligible"><li v-for="item in preview.reasons" :key="item">{{ item }}</li></ul>
              <div v-else class="changes"><p v-for="item in preview.changes" :key="item.sku">{{ item.label }} <b>{{ item.before }}</b><span>→</span><b>{{ item.after }}</b></p><p v-if="!preview.changes.length">数量没有变化；仍会记录此次成功核对的数据日期与来源。</p></div>
              <p class="footnote">{{ preview.thresholdNote || '这是合成导出故障场景。' }} 预检5分钟有效；执行时复查来源摘要和业务版本。</p>
              <label class="check"><input v-model="confirmed" type="checkbox">{{ preview.eligible ? '我确认按以上内容更新演示业务表' : '我确认运行此拒写场景，并保留失败任务用于复核' }}</label>
              <button :disabled="busy || !confirmed" @click="execute">{{ preview.eligible ? '确认执行' : '运行拒写场景并记录' }}</button>
            </div>
          </section>
          </div><section class="card history-card"><div class="section-title"><div><span class="eyebrow">RUN HISTORY</span><h2>任务与人工恢复 <span class="count">{{ runs.length }}</span></h2></div><button class="text-button" :disabled="busy" @click="work(refresh)">刷新记录</button></div>
            <p v-if="!runs.length" class="empty">尚无任务。从正常导出或空导出场景开始。</p>
            <article v-for="run in runs" :key="run.runId" class="run"><div><span :class="['badge', run.status === 'SUCCEEDED' ? 'ok' : 'warn']">{{ statuses[run.status] }}</span><b>{{ run.mode }}</b><span>{{ scenarios[run.scenario] }}</span></div>
              <p>{{ run.errorMessage || run.reason }}</p><small>{{ run.runId }}<span v-if="run.parentRunId"> · 来源 {{ run.parentRunId }}</span><br>{{ run.requestedAt }}</small>
              <div v-if="operator" class="run-actions"><button v-if="run.status === 'FAILED'" class="secondary" @click="retry(run, 'RETRY')">核对后重试</button><button v-if="run.status !== 'RUNNING'" class="text-button" @click="retry(run, 'REFRESH')">人工刷新</button></div>
            </article>
            <details class="audit-details"><summary>查看最近的审计事件（{{ audit.length }}）</summary><div v-for="event in audit" :key="event.id" class="event"><b>{{ event.action }}</b> · {{ event.actor }}<small>{{ event.occurredAt }} / {{ event.runId }}</small></div></details>
          </section>
        </template>
        <section v-else class="card assistant"><span class="eyebrow">EVIDENCE-BASED ASSISTANT</span><h2>查询助手</h2><p>固定查询命令直接读取业务工具；开放式问题仅在配置真实模型后启用。</p>
          <div class="quick"><button v-for="text in ['查询库存', '查询商品', '任务记录', '数据就绪']" :key="text" class="secondary" @click="message = text">{{ text }}</button></div>
          <div v-if="!messages.length" class="chat-empty"><span class="chat-empty-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2V11.5A8.5 8.5 0 0 1 10.5 3h2a8.5 8.5 0 0 1 8.5 8.5Z"/><path d="M7 10h9M7 14h6"/></svg></span><h3>从一个业务问题开始</h3><p>查询库存、商品或任务，展开工具证据核对每个结论。</p></div><article v-for="(item, i) in messages" :key="i" :class="['message', item.role]"><small>{{ item.role === 'user' ? '你' : ({ rule: '规则查询', model: '真实模型', fallback: '降级提示' }[item.mode]) }}</small><p>{{ item.text || item.reply }}</p>
            <details v-if="item.evidence?.length"><summary>查看本次工具证据</summary><pre>{{ JSON.stringify(item.evidence, null, 2) }}</pre></details></article>
          <form class="chat-input" @submit.prevent="ask"><input v-model="message" maxlength="2000" aria-label="查询问题" placeholder="例如：查询库存 DEMO-CUP-01"><button :disabled="busy">查询</button></form>
          <p class="footnote">聊天中输入“确认同步”不会触发写入。实际执行只允许在操作员工作台进行。</p>
        </section>
      </template>
      <footer><span>WORKFLOW REBUILD · 独立公开演示</span><span>Java 21 / Spring Boot / FastAPI / Vue 3</span></footer></div>
    </main>
  </div>
</template>
