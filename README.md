# Warehouse Workflow Rebuild

## 1. 项目定位与核心架构

面向库存同步与库管问答的 Java + Python 双服务重构演示。系统把外部采集、规则问答、敏感意图预检、人工确认和确定性写入串成一条可追踪业务链路。

~~~mermaid
flowchart LR
  U[库管消息] --> R[FastAPI 规则优先路由]
  R -->|未命中| F[最多 5 轮 Function Calling]
  R --> P[只读预检接口]
  P --> C[差异摘要 + SHA-256 凭证]
  C --> W[Vue 工作台人工确认]
  W --> J[Java 状态机与事务写入]
  J --> DB[(业务库)]
  S[采集与文件生成] --> J
~~~

## 2. 核心 AI Agent 与受控工程特性

- 高频固定规则命令直达只读工具，零模型调用；未命中才进入受限 Function Calling 循环。
- 同步、更新库存等敏感写意图在模型循环外拦截，自动生成差异摘要、业务版本、SHA-256 摘要和 5 分钟绑定凭证。
- 凭证可由 operator 一键带入确认区；viewer 只能读取摘要，写入始终由 Java 服务端状态机接管。
- 任务采用唯一键与三态状态机，失败记录追加保留，RETRY/REFRESH 不覆盖历史；重置演示数据会使旧凭证失效。
- Java 主服务负责调度、JWT、状态和数据库写入，Python 服务负责采集、文件生成与 Agent 编排。

## 3. 工作台界面截图

![库存工作台](docs/images/workbench.png)

![移动端工作台](docs/images/workbench-mobile.png)

## 4. 核心源码导航

| 模块 | 位置 |
| --- | --- |
| Agent 路由与 HTTP API | [fastapi-service/app/main.py](fastapi-service/app/main.py) |
| Java 状态机 | [java-app/src/main/java/dev/portfolio/warehouse/Workflow.java](java-app/src/main/java/dev/portfolio/warehouse/Workflow.java) |
| Java 预检与写入接口 | [java-app/src/main/java/dev/portfolio/warehouse/Api.java](java-app/src/main/java/dev/portfolio/warehouse/Api.java) |
| Vue 工作台 | [web-app/src/App.vue](web-app/src/App.vue) |
| 种子数据 | [java-app/src/main/resources/data.sql](java-app/src/main/resources/data.sql) |
| 验证脚本 | [scripts/verify.py](scripts/verify.py) |

## 5. 本地快速启动与自动化验证

~~~powershell
python -m venv .venv
.\\.venv\\Scripts\\Activate.ps1
pip install -r fastapi-service/requirements.txt
npm --prefix web-app ci
npm --prefix web-app run build
mvn -f java-app/pom.xml package
.\\scripts\\start.ps1
~~~

~~~powershell
python scripts/verify.py
python scripts/browser_smoke.py
python scripts/agent-upgrade-browser.py
~~~

完整设计见 [docs/architecture.md](docs/architecture.md)。
