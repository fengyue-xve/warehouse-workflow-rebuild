# Warehouse Workflow Rebuild Architecture

## System topology

~~~mermaid
flowchart TB
  subgraph Client
    Browser[Vue 3 Workbench]
    Operator[Operator]
    Viewer[Viewer]
  end
  subgraph Orchestration
    FastAPI[FastAPI Agent]
    Rules[Deterministic rule router]
    Loop[Function Calling loop]
    PreviewRoute[Sensitive intent interceptor]
  end
  subgraph Java
    Api[Spring Boot API]
    Auth[JWT and role guard]
    Workflow[Preview / execute / reset]
    Tx[Transaction boundary]
  end
  subgraph Data
    H2[(Local H2)]
    Audit[(Audit events)]
  end
  Browser --> Api
  Operator --> Browser
  Viewer --> Browser
  Api --> Auth
  Api --> Workflow
  Api --> FastAPI
  FastAPI --> Rules
  Rules -->|match| Api
  Rules -->|miss| Loop
  Loop -->|read-only tools| Api
  PreviewRoute -->|read-only preview| Workflow
  Workflow --> Tx
  Tx --> H2
  Tx --> Audit
~~~

The browser has one entry point through Java. The Python service exposes export and chat capabilities behind a service key; user authorization is forwarded to Java for each read-only tool call.

## Request routing

1. A fixed command is normalized by <code>route</code> in <code>fastapi-service/app/main.py</code>. It calls one of four read tools and reports the returned evidence.
2. An open question enters <code>model_chat</code> only when a compatible model key is configured. The loop allows at most five rounds, validates tool names and arguments before dispatch, and appends each assistant tool-call message with all corresponding tool results.
3. A message that expresses a synchronization or inventory update intent is intercepted before model dispatch. <code>sensitive_preview</code> asks Java for a read-only card and returns the card to the workbench; no write credential enters the prompt or model evidence.
4. Java remains the authority for writes. <code>Workflow.execute</code> rechecks identity, preview digest, business revision, confirmation, source task and unique task key before entering the transaction.

## Control points

| Boundary | Check | Effect |
| --- | --- | --- |
| Service ingress | Service key and user Bearer token | Separates Python orchestration from user authorization |
| Tool dispatch | Allow-list, object shape and string length | Unknown tools and out-of-range arguments have no business side effect |
| Preview | Source SHA-256, business revision and five-minute expiry | A card cannot be reused after source or business facts change |
| Execution | Role, confirmed flag, mode/source relationship and unique task key | Only an operator can create a task; duplicates return a conflict |
| Persistence | Local transaction with ordered state changes | Inventory, success event and task state commit together |
| Recovery | FAILED records, parentRunId, RETRY and REFRESH | Manual recovery appends a new record and retains history |
| Reset | Operator confirmation, demo baseline reload and revision increment | Old preview credentials become invalid while audit events remain |

## Data model and lifecycle

~~~mermaid
stateDiagram-v2
  [*] --> Preview
  Preview --> Rejected: quality / freshness / duplicate check
  Preview --> AwaitingConfirmation: eligible
  AwaitingConfirmation --> Running: operator confirms
  Running --> Succeeded: local transaction commits
  Running --> Failed: export or quality failure
  Failed --> Preview: RETRY or REFRESH
  Succeeded --> Preview: REFRESH
  AwaitingConfirmation --> [*]: expires or is superseded
~~~

The <code>inventory</code> table stores the latest successful facts. <code>sync_run</code> stores every attempt with a unique task key, status, source digest and parent relation. <code>audit_event</code> records application actions. A failed attempt leaves the last successful inventory intact.

## Agent contract

The Python layer only advertises <code>query_inventory</code>, <code>query_product</code>, <code>query_run</code> and <code>query_readiness</code>. Java validates the same allow-list and argument shape. Chat output is accompanied by tool evidence and stop state; an answer without evidence cannot claim inventory or execution facts.

The model never receives the preview token. The workbench requires an explicit operator confirmation after the card is displayed. Viewer sessions can inspect facts and history but cannot preview or execute a write.

## Local verification

~~~powershell
python scripts/check_public.py
python scripts/verify.py
python scripts/browser_smoke.py
python scripts/agent-upgrade-browser.py
~~~

<code>verify.py</code> runs Python tests, the Vue production build, Java tests/package, a two-service HTTP flow and restart persistence. Browser scripts exercise role boundaries, preview confirmation, sensitive-intent interception, mobile layout and the reset baseline.
