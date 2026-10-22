# Architecture

~~~mermaid
flowchart TB
  Chat[库管问答] --> Route{规则命中?}
  Route -->|是| Read[只读查询]
  Route -->|否| Loop[最多 5 轮 Tool Calling]
  Chat --> Sensitive{敏感写意图?}
  Sensitive -->|是| Preflight[只读预检]
  Preflight --> Card[revision + SHA-256 + expiry]
  Card --> Confirm[operator 确认区]
  Confirm --> State[Java 状态机]
  State --> Store[(业务数据库)]
  Collector[Python 采集] --> State
~~~

## Agent route

固定命令使用规则路由，直接返回库存、销量和任务状态。自然语言问题进入受限循环，工具参数在分发层校验，未知工具和越界参数立即终止。敏感写意图永远走预检接口，不允许模型直接执行。

## Control points

预检卡片绑定用户、revision 与过期时间；确认时重新核对摘要。任务键保证幂等，写入前检查状态、版本和重复 pending。失败任务追加审计快照，RESET 会清理演示业务状态并使旧凭证失效。

## Run and verify

先执行根目录 README 的启动命令，再运行 scripts/verify.py、浏览器 smoke 和 Agent upgrade smoke。
