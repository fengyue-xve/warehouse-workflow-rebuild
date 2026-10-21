"""虚构导出适配器与只读 Agent；不接飞书，不读公司数据，不加载 .env。"""
from __future__ import annotations

import json
import os
import re
import secrets
from datetime import date, timedelta
from typing import Literal

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

app = FastAPI(title="Warehouse synthetic capabilities", version="1.0.0")
SERVICE_KEY = os.getenv("DEMO_SERVICE_KEY", "local-synthetic-demo-service")
JAVA_URL = os.getenv("JAVA_URL", "http://127.0.0.1:18080").rstrip("/")
READ_TOOLS = {
    "query_inventory": ("查询演示库存，返回数据日期、来源和业务日；可按sku过滤", {"sku": {"type": "string", "maxLength": 48}}),
    "query_product": ("查询演示商品及箱规；可按sku过滤", {"sku": {"type": "string", "maxLength": 48}}),
    "query_run": ("查询最近的同步任务、拒写原因和人工重跑来源", {}),
    "query_readiness": ("查询库存数据是否覆盖当前演示业务日；不表示已发货", {}),
}
TOOLS = [{"type": "function", "function": {"name": name, "description": spec[0], "parameters": {
    "type": "object", "properties": spec[1], "additionalProperties": False}}} for name, spec in READ_TOOLS.items()]
SYSTEM = (
    "你是电商库管库存核对助手。所有数据都是合成演示。仅有只读工具。"
    "库存、日期和任务事实必须来自工具，参考聊天中的数字不能覆盖工具。"
    "说明数据日期和业务日；旧库存不能称为实时库存。失败任务不改变上次成功的事实。"
    "同步和重跑只能引导用户在工作台预检、选择来源任务并确认；不能调用写接口。"
    "库存就绪不表示订单已出库、已到货或能承诺交付日期。未知信息说明缺失。"
)


def service_auth(x_service_key: str = Header(default="")) -> None:
    if not secrets.compare_digest(x_service_key, SERVICE_KEY):
        raise HTTPException(401, "Service authentication required")


class ExportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scenario: Literal["normal", "empty", "stale", "duplicate", "spike", "unavailable"]
    businessDate: date


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message: str = Field(min_length=1, max_length=2000)


@app.get("/health")
def health():
    return {"status": "ok", "synthetic": True}


@app.post("/exports", dependencies=[Depends(service_auth)])
def export(body: ExportRequest):
    if body.scenario == "unavailable":
        raise HTTPException(503, "Synthetic export unavailable")
    rows = [{"sku": "DEMO-CUP-01", "quantity": 100}, {"sku": "DEMO-LAMP-01", "quantity": 40},
            {"sku": "DEMO-BAG-01", "quantity": 90}]
    if body.scenario == "empty":
        rows = []
    elif body.scenario == "duplicate":
        rows.append(dict(rows[0]))
    elif body.scenario == "spike":
        rows = [{"sku": row["sku"], "quantity": 5000} for row in rows]
    data_date = body.businessDate - timedelta(days=2) if body.scenario == "stale" else body.businessDate
    return {"source": "synthetic-warehouse", "dataDate": data_date.isoformat(), "synthetic": True, "rows": rows}


async def call_tool(name: str, args: object, authorization: str) -> dict:
    """两道防线：编排白名单拦截；Java 再验证会话与工具/参数。"""
    if name not in READ_TOOLS:
        return {"error": "TOOL_DENIED", "message": "模型无同步或文件生成执行权，请在工作台操作"}
    if not isinstance(args, dict) or set(args) - set(READ_TOOLS[name][1]):
        return {"error": "BAD_TOOL_ARGS"}
    if any(not isinstance(v, str) or len(v) > 48 for v in args.values()):
        return {"error": "BAD_TOOL_ARGS"}
    try:
        async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
            response = await client.post(f"{JAVA_URL}/api/tools/{name}", json={"args": args},
                                         headers={"Authorization": authorization})
            if response.status_code == 401:
                raise HTTPException(401, "User session expired")
            if response.status_code != 200:
                return {"error": "JAVA_TOOL_FAILED", "httpStatus": response.status_code}
            return response.json()
    except httpx.HTTPError:
        return {"error": "JAVA_TOOL_UNAVAILABLE"}


def route(message: str) -> tuple[str | None, dict]:
    """只有明确规则命令直接执行；开放式业务提问交给可选真实模型。"""
    if message in {"数据就绪", "数据是否就绪"}:
        return "query_readiness", {}
    if message in {"任务记录", "同步结果"}:
        return "query_run", {}
    match = re.fullmatch(r"(查询库存|查询商品)(?:\s+(DEMO-[A-Z0-9-]+))?", message)
    if match:
        return ("query_inventory" if match[1] == "查询库存" else "query_product"), ({"sku": match[2]} if match[2] else {})
    return None, {}


def describe(name: str, data: dict) -> str:
    if data.get("error"):
        return f"查询未完成（{data['error']}），不能据此判断库存或任务成功。"
    if name == "query_readiness":
        status = "已覆盖" if data.get("ready") else "尚未覆盖"
        latest = data.get("lastAttempt") or {}
        return (f"合成演示：库存数据{status}业务日 {data.get('businessDate')}。"
                f"最近任务状态：{latest.get('status', '尚未运行')}。库存核对就绪不表示订单已发货，也不构成交期承诺。")
    if name == "query_run":
        runs = data.get("items", [])
        if not runs:
            return "合成演示：尚无同步任务，不能把初始种子数据称为已完成同步。"
        latest = runs[0]
        return (f"合成演示：最近任务 {latest['runId']}，状态 {latest['status']}，"
                f"原因：{latest.get('errorMessage') or '任务完成'}。失败后请核对原因，在工作台选择来源任务重试，原记录保留。")
    items = data.get("items", [])
    if not items:
        return "合成演示：未查到该 SKU；查无记录不代表库存为零。"
    return "合成演示，业务日 " + data["businessDate"] + "：\n" + "\n".join(
        f"{i['sku']} {i['label']}：库存 {i['quantity']}，数据日期 {i['dataDate']}，来源 {i['source']}，"
        f"每箱 {i['packSize']} 件，{'日期匹配' if i['fresh'] else '旧数据，需核对'}。" for i in items)


async def complete(messages: list[dict]) -> dict:
    api_key = os.getenv("LLM_API_KEY", "")
    endpoint = os.getenv("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/")
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(endpoint + "/chat/completions", headers={"Authorization": "Bearer " + api_key},
            json={"model": os.getenv("LLM_MODEL", "deepseek-chat"), "messages": messages,
                  "tools": TOOLS, "tool_choice": "auto", "temperature": 0, "max_tokens": 1000})
        response.raise_for_status()
        return response.json()["choices"][0]["message"]


def routing(calls: int = 0) -> dict:
    return {"modelCalls": calls, "maxRounds": 5, "tokenUsage": 0 if calls == 0 else None}


async def sensitive_preview(authorization: str) -> dict:
    # This branch is outside the model loop. The credential never enters a prompt,
    # a model tool result, or a trace. Java decides whether the user may confirm.
    try:
        async with httpx.AsyncClient(timeout=8, trust_env=False, follow_redirects=False) as client:
            response = await client.post(JAVA_URL + "/api/sync/preview-read", json={}, headers={"Authorization": authorization})
            response.raise_for_status()
            card = response.json()
        if card.get("kind") != "sync_preview" or not re.fullmatch(r"[0-9a-f]{64}", card.get("preview", {}).get("sourceDigest", "")):
            raise ValueError("invalid_preview_card")
        return {"mode": "intercepted", "reply": "已拦截直接写入，只完成只读预检。请核对差异并带入操作员确认区，勾选后才可执行；当前库存未改变。",
                "previewCard": card, "trace": [{"tool": "read_only_sync_preview", "ok": True}], "stopReason": "human_confirmation_required", "routing": routing()}
    except (httpx.HTTPError, ValueError, TypeError):
        return {"mode": "intercepted", "reply": "已拦截直接写入，但预检暂不可用。请检查来源服务或人工进入工作台；没有更新库存。",
                "trace": [], "stopReason": "preview_unavailable", "routing": routing()}


async def model_chat(message: str, authorization: str, stats: dict | None = None) -> dict:
    stats = stats if stats is not None else {"modelCalls": 0}
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": message}]
    trace = []
    evidence = []
    for _ in range(5):
        stats["modelCalls"] += 1
        response = await complete(messages)
        calls = response.get("tool_calls") or []
        if not calls:
            # 没有读到事实时不能把模型的自由回答当成库存或执行证据。
            if not evidence:
                return {"mode": "model", "reply": "未取得业务工具证据，无法确认库存或执行结果。请使用查询命令或在工作台预检。",
                        "trace": trace, "stopReason": "no_evidence"}
            return {"mode": "model", "reply": response.get("content") or "模型未形成结论，请查看证据。",
                    "trace": trace, "evidence": evidence, "stopReason": "completed"}
        if len(calls) > 4:
            return {"mode": "model", "reply": "单轮工具调用超出上限，请缩小问题。", "trace": trace, "stopReason": "tool_budget"}
        messages.append({"role": "assistant", "content": response.get("content"), "tool_calls": calls})
        for call in calls:
            name = call.get("function", {}).get("name", "")
            try:
                args = json.loads(call.get("function", {}).get("arguments", "{}"))
            except (json.JSONDecodeError, TypeError):
                args = None
            data = await call_tool(name, args, authorization)
            trace.append({"tool": name, "ok": not bool(data.get("error"))})
            if not data.get("error"):
                evidence.append({"tool": name, "data": data})
            messages.append({"role": "tool", "tool_call_id": call.get("id", ""), "content": json.dumps(data, ensure_ascii=False)})
    return {"mode": "model", "reply": "达到5轮调用上限，请查看证据并拆分问题。", "trace": trace,
            "evidence": evidence, "stopReason": "round_limit"}


@app.post("/agent/chat", dependencies=[Depends(service_auth)])
async def chat(body: ChatRequest, authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "User session required")
    message = body.message.strip()
    if re.search(r"执行同步|同步(?:一下)?库存|更新库存|刷新库存|确认同步|重跑(?:库存|同步)?|重试同步", message):
        return await sensitive_preview(authorization)
    name, args = route(message)
    if name:
        data = await call_tool(name, args, authorization)
        return {"mode": "rule", "reply": describe(name, data), "evidence": [{"tool": name, "data": data}],
                "trace": [{"tool": name, "ok": not bool(data.get("error"))}], "stopReason": "rule_match", "routing": routing()}
    if not os.getenv("LLM_API_KEY"):
        return {"mode": "fallback", "reply": "当前未启用真实模型。可用：查询库存、查询商品、任务记录、数据就绪。同步与重跑请在工作台预检并确认。",
                "trace": [], "stopReason": "model_not_configured", "routing": routing()}
    stats = {"modelCalls": 0}
    try:
        result = await model_chat(message, authorization, stats)
        result["routing"] = routing(stats["modelCalls"])
        return result
    except (httpx.HTTPError, KeyError, ValueError, TypeError):
        return {"mode": "fallback", "reply": "模型服务暂不可用。可继续使用查询库存、任务记录等固定命令；没有执行写操作。",
                "trace": [], "stopReason": "model_error", "routing": routing(stats["modelCalls"])}
