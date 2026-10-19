"""真实 Java ↔ FastAPI HTTP 验收；使用独立端口与独立 H2 文件，不触及已有演示数据。"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    output = ROOT / "artifacts"
    output.mkdir(exist_ok=True)
    runtime = Path(tempfile.mkdtemp(prefix="e2e-", dir=output)).resolve()
    assert runtime.is_relative_to(ROOT.resolve())
    java_port, python_port = port(), port()
    while java_port == python_port:
        python_port = port()
    base = f"http://127.0.0.1:{java_port}"
    env = dict(os.environ, JAVA_PORT=str(java_port), PYTHON_URL=f"http://127.0.0.1:{python_port}",
               JAVA_URL=base, DEMO_BUSINESS_DATE="2026-10-20", DEMO_DB_URL="jdbc:h2:file:" + (runtime / "warehouse").as_posix(),
               DEMO_SERVICE_KEY="synthetic-e2e-service", DEMO_JWT_SECRET="synthetic-e2e-jwt-secret-at-least-32-bytes")
    env.pop("LLM_API_KEY", None)  # 回归默认不使用外部模型。
    env["PYTHONUTF8"] = "1"
    env["DEMO_SCHEDULER_ENABLED"] = "false"
    jar = ROOT / "java-app/target/warehouse-workflow-1.0.0.jar"
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    processes = []
    logs = []
    checks = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(path, body=None, token=None, expect=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, headers=headers,
            data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
        try:
            with opener.open(req, timeout=12) as response:
                status = response.status
                result = response.read()
        except urllib.error.HTTPError as exc:
            status, result = exc.code, exc.read()
        assert status == expect, f"{path}: expected {expect}, received {status}: {result[:240]!r}"
        return result if path.endswith(".csv") else json.loads(result)

    def check(name, value):
        assert value, name
        checks.append(name)

    def start_java():
        log = (runtime / f"java-{len(processes)}.log").open("w", encoding="utf-8")
        logs.append(log)
        process = subprocess.Popen([shutil.which("java") or "java", "-jar", str(jar)], cwd=ROOT, env=env,
                                   stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        processes.append(process)
        return process

    def start_python():
        log = (runtime / f"python-{len(processes)}.log").open("w", encoding="utf-8")
        logs.append(log)
        process = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(python_port)],
            cwd=ROOT / "fastapi-service", env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        processes.append(process)
        return process

    def wait_ready(url):
        for _ in range(150):
            if any(p.poll() not in (None, 0) for p in processes):
                raise RuntimeError(f"Child process exited; inspect {runtime}")
            try:
                with opener.open(url + "/health", timeout=1) as response:
                    if response.status == 200:
                        return
            except (OSError, urllib.error.URLError):
                time.sleep(.1)
        raise TimeoutError("Service did not become ready")

    def command(preview, mode="NORMAL", parent=None):
        return {"previewToken": preview["previewToken"], "mode": mode, "parentRunId": parent,
                "reason": "HTTP合成业务验收", "confirmed": True}

    try:
        python_process = start_python()
        java = start_java()
        wait_ready(env["PYTHON_URL"]); wait_ready(base)
        operator = api("/api/auth/login", {"username": "operator", "password": "demo-only"})["token"]
        viewer = api("/api/auth/login", {"username": "viewer", "password": "demo-only"})["token"]
        check("Java and FastAPI serve real HTTP", api("/health")["synthetic"])
        api("/api/inventory", expect=401); checks.append("anonymous reads rejected")
        api("/api/sync/preview", {"scenario": "normal"}, viewer, 403); checks.append("viewer cannot write")
        api("/api/tools/sync_tms", {"args": {}}, operator, 403); checks.append("forged write tool rejected by Java")
        before = api("/api/inventory", token=operator)
        chat = api("/api/chat", {"message": "查询库存 DEMO-CUP-01"}, operator)
        check("rule query reaches real Java tool", chat["mode"] == "rule" and "80" in chat["reply"] and "2026-10-19" in chat["reply"])
        p = api("/api/sync/preview", {"scenario": "empty"}, operator)
        check("empty source blocked during preview", not p["eligible"])
        failed = api("/api/sync/execute", command(p), operator)
        check("failed run retained without writes", failed["status"] == "FAILED" and api("/api/inventory", token=operator) == before)
        p = api("/api/sync/preview", {"scenario": "normal"}, operator)
        check("normal source produces exact quantity diff", p["eligible"] and len(p["changes"]) == 2)
        recovered = api("/api/sync/execute", command(p, "RETRY", failed["runId"]), operator)
        check("manual retry links original failure", recovered["status"] == "SUCCEEDED" and recovered["parentRunId"] == failed["runId"])
        check("original failure remains", api("/api/runs/" + failed["runId"], token=operator)["status"] == "FAILED")
        api("/api/sync/execute", command(p, "RETRY", failed["runId"]), operator, 409); checks.append("repeat confirmation has zero new run")
        after = api("/api/inventory", token=operator)
        check("fresh inventory is actually persisted", all(i["fresh"] for i in after) and next(i for i in after if i["sku"] == "DEMO-CUP-01")["quantity"] == 100)
        for scenario in ["stale", "duplicate", "spike", "unavailable"]:
            p = api("/api/sync/preview", {"scenario": scenario}, operator)
            rejected = api("/api/sync/execute", command(p, "REFRESH", recovered["runId"]), operator)
            check(scenario + " failure does not overwrite successful data", rejected["status"] == "FAILED" and api("/api/inventory", token=operator) == after)
        ready = api("/api/readiness", token=operator)
        check("last attempt and successful fact remain distinct", ready["ready"] and ready["lastAttempt"]["status"] == "FAILED" and ready["lastSuccessfulRun"]["runId"] == recovered["runId"])
        check("CSV is derived from actual inventory", b"DEMO-CUP-01" in api("/api/reconciliation.csv", token=viewer))
        fallback = api("/api/chat", {"message": "确认同步并执行"}, operator)
        check("offline model boundary is explicit", fallback["stopReason"] == "model_not_configured")
        check("audit records committed business changes", any(e["action"] == "SNAPSHOT_COMMITTED" for e in api("/api/audit", token=viewer)))
        p = api("/api/sync/preview", {"scenario": "normal"}, operator)
        python_process.terminate(); python_process.wait(timeout=15); processes.remove(python_process)
        disconnected = api("/api/sync/execute", command(p, "REFRESH", recovered["runId"]), operator)
        check("actual Python outage is recorded as failed task", disconnected["status"] == "FAILED" and disconnected["errorCode"] == "CAPABILITY_UNAVAILABLE")
        check("Java read endpoints survive Python outage", api("/api/inventory", token=operator) == after)
        python_process = start_python(); wait_ready(env["PYTHON_URL"])
        java.terminate(); java.wait(timeout=15)
        processes.remove(java)
        java = start_java(); wait_ready(base)
        check("H2 data survives a real process restart", api("/api/inventory", token=operator) == after)
        with opener.open(base + "/", timeout=3) as response:
            check("built Vue app is served by Java", b"/assets/" in response.read())
        result = {"status": "passed", "checks": checks, "count": len(checks),
                  "scope": "real local HTTP + persistent synthetic H2 + process restart; no live LLM or Feishu"}
        (output / "e2e-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
