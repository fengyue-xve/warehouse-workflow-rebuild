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
      <div class="brand"><span class="brand-icon">库</span><div>库管作业<span class="brand-sub">WORKFLOW REBUILD</span></div></div>
      <div class="nav-label">作业空间</div>
      <button :class="['nav', { active: tab === 'workflow' }]" @click="tab = 'workflow'">01　库存同步与核对</button>
      <button :class="['nav', { active: tab === 'assistant' }]" @click="tab = 'assistant'">02　只读查询助手</button>
      <div class="sidebar-note"><span class="dot"></span> 独立公开演示<p>所有商品、数据与场景均为虚构。<br>不连接公司系统或真实飞书。</p></div>
    </aside>
    <main>
      <header><div><span class="eyebrow">电商库管 / 业务数据准备</span><h1>{{ tab === 'workflow' ? '先核对，再更新。' : '每个结论，都有数据依据。' }}</h1></div>
        <div v-if="session" class="user">{{ session.username }} · {{ operator ? '操作员' : '只读观察员' }} <button class="text-button" @click="logout">退出</button></div>
      </header>
      <div v-if="error" class="alert error" role="alert">{{ error }}</div>
      <div v-if="notice" class="alert success" role="status">{{ notice }}</div>
      <section v-if="!session" class="card login">
        <span class="eyebrow">进入本地演示</span><h2>从一批库存更新开始</h2><p>观察数据如何经过校验进入业务表，以及失败如何被保留和恢复。</p>
        <form @submit.prevent="login"><label>演示身份<select v-model="username"><option>operator</option><option>viewer</option></select></label>
          <label>演示口令<input v-model="password" type="password" autocomplete="current-password"></label><button :disabled="busy">进入工作台</button></form>
        <small>本地公开样例口令：demo-only。演示配置仅供本机运行。</small>
      </section>
      <template v-else>
        <div class="metrics"><div class="metric"><span>演示业务日</span><strong>{{ readiness.businessDate || '—' }}</strong><small>固定业务时钟，便于重复演示</small></div>
          <div class="metric"><span>库存数据准备</span><strong :class="readiness.ready ? 'green' : 'amber'">{{ readiness.ready ? '日期已匹配' : '需要更新' }}</strong><small>按每个 SKU 的数据日期判断</small></div>
          <div class="metric"><span>最近任务</span><strong>{{ statuses[readiness.lastAttempt?.status] || '尚未运行' }}</strong><small>失败不覆盖上次成功的数据事实</small></div></div>
        <template v-if="tab === 'workflow'">
          <section class="card"><div class="section-title"><div><span class="eyebrow">01 / 当前业务表</span><h2>库存核对</h2></div><button class="secondary" :disabled="busy" @click="download">导出核对 CSV</button></div>
            <div class="table-wrap"><table><thead><tr><th>商品 / SKU</th><th>库存</th><th>每箱</th><th>数据日期</th><th>来源 / 时效</th></tr></thead>
              <tbody><tr v-for="item in inventory" :key="item.sku"><td><b>{{ item.label }}</b><small>{{ item.sku }}</small></td><td class="number">{{ item.quantity }}</td><td>{{ item.packSize }} 件</td><td>{{ item.dataDate }}</td><td><span :class="['badge', item.fresh ? 'ok' : 'warn']">{{ item.fresh ? '日期匹配' : '旧数据' }}</span><small>{{ item.source }}</small></td></tr></tbody></table></div>
            <p class="footnote">这里只证明库存数据准备情况，不表示订单已发货、已到货或可以承诺交期。</p>
          </section>
          <section class="card"><div class="section-title"><div><span class="eyebrow">02 / 人工操作</span><h2>预检变更，再确认执行</h2></div><span class="badge neutral">模型没有执行权限</span></div>
            <p v-if="!operator">viewer 可以查询数据与任务；请使用 operator 身份体验预检与确认。</p>
            <form v-else @submit.prevent="inspect">
              <div class="form-grid"><label>导出场景<select v-model="scenario" @change="invalidate"><option v-for="(label, key) in scenarios" :key="key" :value="key">{{ label }}</option></select></label>
                <label>执行模式<select v-model="mode" @change="invalidate"><option value="NORMAL">普通同步 · 当日唯一</option><option value="RETRY">失败重试 · 追加记录</option><option value="REFRESH">人工刷新 · 追加记录</option></select></label>
                <label v-if="mode !== 'NORMAL'">来源任务<select v-model="parentRunId" required @change="invalidate"><option value="" disabled>请选择来源任务</option><option v-for="run in runs.filter(r => mode !== 'RETRY' || r.status === 'FAILED')" :key="run.runId" :value="run.runId">{{ statuses[run.status] }} / {{ run.runId.slice(0, 8) }}</option></select></label>
                <label class="wide">执行原因<input v-model="reason" maxlength="240" required placeholder="说明为什么需要这次操作"></label></div>
              <button :disabled="busy || (mode !== 'NORMAL' && !parentRunId)">预检来源与变更</button>
            </form>
            <div v-if="preview" class="preview">
              <h3>{{ preview.eligible ? '预检通过，等待你确认' : '发现问题：业务表将保持原状' }}</h3>
              <ul v-if="!preview.eligible"><li v-for="item in preview.reasons" :key="item">{{ item }}</li></ul>
              <div v-else class="changes"><p v-for="item in preview.changes" :key="item.sku">{{ item.label }} <b>{{ item.before }}</b><span>→</span><b>{{ item.after }}</b></p><p v-if="!preview.changes.length">数量没有变化；仍会记录此次成功核对的数据日期与来源。</p></div>
              <p class="footnote">{{ preview.thresholdNote || '这是合成导出故障场景。' }} 预检5分钟有效；执行时复查来源摘要和业务版本。</p>
              <label class="check"><input v-model="confirmed" type="checkbox">{{ preview.eligible ? '我确认按以上内容更新演示业务表' : '我确认运行此拒写场景，并保留失败任务用于复核' }}</label>
              <button :disabled="busy || !confirmed" @click="execute">{{ preview.eligible ? '确认执行' : '运行拒写场景并记录' }}</button>
            </div>
          </section>
          <section class="card"><div class="section-title"><div><span class="eyebrow">03 / 可追溯的结果</span><h2>任务与人工恢复</h2></div><button class="text-button" :disabled="busy" @click="work(refresh)">刷新记录</button></div>
            <p v-if="!runs.length" class="empty">尚无任务。从正常导出或空导出场景开始。</p>
            <article v-for="run in runs" :key="run.runId" class="run"><div><span :class="['badge', run.status === 'SUCCEEDED' ? 'ok' : 'warn']">{{ statuses[run.status] }}</span><b>{{ run.mode }}</b><span>{{ scenarios[run.scenario] }}</span></div>
              <p>{{ run.errorMessage || run.reason }}</p><small>{{ run.runId }}<span v-if="run.parentRunId"> · 来源 {{ run.parentRunId }}</span><br>{{ run.requestedAt }}</small>
              <div v-if="operator" class="run-actions"><button v-if="run.status === 'FAILED'" class="secondary" @click="retry(run, 'RETRY')">核对后重试</button><button v-if="run.status !== 'RUNNING'" class="text-button" @click="retry(run, 'REFRESH')">人工刷新</button></div>
            </article>
            <details><summary>查看最近的审计事件（{{ audit.length }}）</summary><div v-for="event in audit" :key="event.id" class="event"><b>{{ event.action }}</b> · {{ event.actor }}<small>{{ event.occurredAt }} / {{ event.runId }}</small></div></details>
          </section>
        </template>
        <section v-else class="card assistant"><span class="eyebrow">只读工具 / 有依据的解释</span><h2>查询助手</h2><p>固定查询命令直接读取业务工具；开放式问题仅在配置真实模型后启用。</p>
          <div class="quick"><button v-for="text in ['查询库存', '查询商品', '任务记录', '数据就绪']" :key="text" class="secondary" @click="message = text">{{ text }}</button></div>
          <article v-for="(item, i) in messages" :key="i" :class="['message', item.role]"><small>{{ item.role === 'user' ? '你' : ({ rule: '规则查询', model: '真实模型', fallback: '降级提示' }[item.mode]) }}</small><p>{{ item.text || item.reply }}</p>
            <details v-if="item.evidence?.length"><summary>查看本次工具证据</summary><pre>{{ JSON.stringify(item.evidence, null, 2) }}</pre></details></article>
          <form class="chat-input" @submit.prevent="ask"><input v-model="message" maxlength="2000" aria-label="查询问题" placeholder="例如：查询库存 DEMO-CUP-01"><button :disabled="busy">查询</button></form>
          <p class="footnote">聊天中输入“确认同步”不会触发写入。实际执行只允许在操作员工作台进行。</p>
        </section>
      </template>
      <footer>独立重设计的实习经验演示 · Java 21 / Spring Boot / FastAPI / Vue 3 · 合成数据</footer>
    </main>
  </div>
</template>
