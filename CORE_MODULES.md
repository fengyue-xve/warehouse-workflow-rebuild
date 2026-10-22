# Core Modules

## Repository scope

Warehouse Workflow Rebuild is a Java + Python + Vue reference implementation for an inventory synchronization workflow. The public surface contains the executable services, the guarded workbench, architecture notes and repeatable verification commands. The root MIT <code>LICENSE</code> is part of the distribution and must remain unchanged.

## Upstream or retained foundation

No separate upstream repository or NOTICE file is declared in this checkout. The retained foundation is the existing application stack and schema:

| Foundation | Location | Role |
| --- | --- | --- |
| Spring Boot HTTP entry point | <code>java-app/src/main/java/dev/portfolio/warehouse/Application.java</code> | Serves APIs and the built workbench |
| JDBC/H2 schema | <code>java-app/src/main/resources/schema.sql</code> | Catalog, inventory, run, audit and revision tables |
| Vue 3/Vite shell | <code>web-app/src/main.js</code>, <code>web-app/src/App.vue</code> | Browser session and workbench rendering |
| FastAPI service shell | <code>fastapi-service/app/main.py</code> | Service-key protected export and agent endpoint |
| MIT license | <code>LICENSE</code> | Copyright and redistribution terms |

The foundation is documented as retained code; this map does not assign upstream authorship where no upstream manifest is present.

## Personal additions and refactors

| Module | Files | Resume highlight |
| --- | --- | --- |
| Rule-first Agent router | <code>fastapi-service/app/main.py</code> | Fixed inventory/task commands bypass model calls; open questions use a bounded Function Calling loop |
| Sensitive-intent preflight | <code>fastapi-service/app/main.py</code>, <code>java-app/src/main/java/dev/portfolio/warehouse/Api.java</code> | Intercepts sync/update language and creates a read-only preflight card |
| SHA-256 credential binding | <code>java-app/src/main/java/dev/portfolio/warehouse/Workflow.java</code> | Binds source digest, revision, actor and five-minute expiry before any write |
| Deterministic workflow state machine | <code>java-app/src/main/java/dev/portfolio/warehouse/Workflow.java</code>, <code>java-app/src/main/resources/schema.sql</code> | Keeps FAILED history, supports RETRY/REFRESH append semantics and reset invalidation |
| Tool bridge and auth boundary | <code>java-app/src/main/java/dev/portfolio/warehouse/ExportGateway.java</code>, <code>java-app/src/main/java/dev/portfolio/warehouse/Auth.java</code> | Keeps Python orchestration read-only while Java owns user authorization |
| Human-in-the-loop workbench | <code>web-app/src/App.vue</code>, <code>web-app/src/style.css</code> | Shows evidence, changes, confirmation state and role-specific controls |
| Regression/e2e harness | <code>fastapi-service/tests/**</code>, <code>java-app/src/test/**</code>, <code>scripts/e2e.py</code> | Verifies tool contracts, preview binding, role boundaries, restart persistence and reset flow |

## Core flow to explain in an interview

~~~mermaid
sequenceDiagram
  participant User as Operator
  participant UI as Vue Workbench
  participant Java as Java API
  participant Py as FastAPI Agent
  participant DB as H2
  User->>UI: "确认同步库存"
  UI->>Java: login / chat
  Java->>Py: authenticated chat request
  Py-->>Java: sensitive intent intercepted
  Java->>DB: read inventory + revision
  Java-->>UI: changes + SHA-256 + expiry
  User->>UI: inspect and confirm
  UI->>Java: execute(previewToken, confirmed=true)
  Java->>DB: validate and commit transaction
  Java-->>UI: task status + audit event
~~~

## Public boundary and internal material

The public tree retains <code>LICENSE</code>, source files, tests, screenshots, README and <code>docs/architecture.md</code>. The following material belongs in local <code>docs/internal/</code> and is ignored by Git:

- Legacy development notes: <code>MY_CHANGES.md</code>, <code>UPSTREAM.md</code>, <code>README.upstream.md</code>.
- Provenance and dated audit snapshots: <code>docs/**/05-provenance.md</code>, <code>docs/portfolio/*provenance*.md</code>, <code>docs/*.json</code> with date stamps, <code>agent-windows-baseline*</code>.
- In this checkout, the review and interface notes moved under <code>docs/internal/</code>: <code>03-learning-guide.md</code>, <code>04-verification.md</code>, <code>06-interface-design.md</code>, <code>08-agent-upgrade.md</code>.
- Local evaluation output, process logs and generated databases remain ignored.

## License and ownership checks

- Keep <code>LICENSE</code> unchanged in the root and retain any upstream copyright header encountered in source files.
- Do not add an upstream author claim without a corresponding NOTICE or repository manifest.
- Run <code>python scripts/check_public.py</code> before publishing; it checks tracked files for credentials, private local paths and runtime artifacts.
