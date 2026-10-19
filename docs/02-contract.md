# 接口、状态与权限

## 入口

浏览器仅访问 Java。除 `/health`、登录和静态资源，所有业务API要求有效的会话JWT。`viewer`和`operator`可查询，预检与执行仅限`operator`。

Python 导出和对话入口要求 `X-Service-Key`。对话内的Java工具调用透传用户Bearer，Python不解析或签发用户JWT。模型没有Service Key或执行入口。

| 方法与路径 | 权限 | 作用 |
| --- | --- | --- |
| POST `/api/auth/login` | 演示账号 | `username/password`换会话 |
| GET `/api/inventory` | 已登录 | 已持久化的库存事实 |
| GET `/api/readiness` | 已登录 | 数据业务日与最近/成功任务 |
| GET `/api/runs`、`/api/runs/{id}` | 已登录 | 执行历史与结果 |
| GET `/api/audit` | 已登录 | 应用层追加的审计事件 |
| GET `/api/reconciliation.csv` | 已登录 | 从真实快照派生的库存核对表 |
| POST `/api/sync/preview` | operator | `scenario`选择合成导出并预检 |
| POST `/api/sync/execute` | operator | 校验预检并按明确模式执行 |
| POST `/api/chat` | 已登录 | 代理到Python查询助手 |
| POST `/api/tools/{name}` | 已登录 | 只读工具白名单与参数校验 |

执行请求示例：

```json
{
  "previewToken": "<预检返回值，不应写入公开日志>",
  "mode": "RETRY",
  "parentRunId": "<FAILED来源任务的runId>",
  "reason": "核对导出失败原因后重新取得正常数据",
  "confirmed": true
}
```

`NORMAL`不接受来源任务；`RETRY`只接受当前业务日的失败来源；`REFRESH`只接受当前业务日的终态来源。每次成功受理都会追加任务。任务键、预检使用键的唯一约束防重复；单实例锁保证同步执行不相互穿插。

## 运行状态

```text
创建并提交 RUNNING
    ├─ 导出/校验/来源摘要/版本检查失败 → FAILED（原快照保持）
    └─ 本地写事务：快照 + 样本 + 成功审计 + SUCCEEDED

FAILED --人工预检和确认--> 新 RETRY 任务，原任务保留
SUCCEEDED/FAILED --人工预检和确认--> 新 REFRESH 任务
```

`200 + status=FAILED`表示执行请求已受理且有失败记录，不能被视为同步成功。请求本身被拒绝（缺权限、重复、缺确认等）使用4xx。

## 关键错误

| 错误 | HTTP/运行结果 | 行为 |
| --- | --- | --- |
| 缺失或过期会话 | 401 | 不读取业务，不创建任务 |
| viewer写操作 | 403 | 不预检，不执行 |
| 伪造模型写工具 | 403 | 不进入同步执行 |
| 确认缺失、参数不合规 | 422 | 不创建任务 |
| 重复任务/预检复用 | 409 | 不追加执行 |
| 预检过期或签名无效 | 409 | 重新预检，不清除有效登录 |
| 预检后来源或业务表变化 | 失败任务/对应错误码 | 不改变库存，要求重新核对 |
| QUALITY_BLOCKED | FAILED | 已记录拒写原因，库存保持 |
| CAPABILITY_UNAVAILABLE | FAILED | 实际能力服务断开，保留失败 |
| INTERRUPTED | FAILED | 上次进程中断，等待人工处理 |

会话JWT与预检JWT有不同`purpose`，不能互相当作凭证。审计只保证应用路径追加，没有数据库权限级的不可篡改保证。

## Agent 契约

只允许 `query_inventory(sku?)`、`query_product(sku?)`、`query_run()`、`query_readiness()`；Java再次校验工具名和字段。编排只接受已登记参数；未知调用没有HTTP副作用。

固定命令：`查询库存 [SKU]`、`查询商品 [SKU]`、`任务记录`/`同步结果`、`数据就绪`。开放式问题仅在配置模型时进入真实调用。每轮assistant的工具调用整体入历史，再补齐各tool结果。

模型回答附带本次实际工具证据。它仍可能误解证据，因此协议测试与权限测试不替代业务答案质量评测。本演示没有跨请求对话记忆或RAG。
