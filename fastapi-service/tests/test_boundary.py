import asyncio
import copy
import json
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app import main


def request(method, path, **kwargs):
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test") as client:
            return await client.request(method, path, **kwargs)
    return asyncio.run(run())


def test_export_requires_service_auth():
    assert request("POST", "/exports", json={"scenario": "normal", "businessDate": "2026-10-20"}).status_code == 401


@pytest.mark.parametrize("scenario", ["normal", "empty", "stale", "duplicate", "spike"])
def test_exports_are_deterministic_synthetic_data(scenario):
    body = {"scenario": scenario, "businessDate": "2026-10-20"}
    headers = {"X-Service-Key": main.SERVICE_KEY}
    one = request("POST", "/exports", json=body, headers=headers)
    two = request("POST", "/exports", json=body, headers=headers)
    assert one.status_code == 200 and one.json() == two.json()
    assert one.json()["synthetic"] is True


@pytest.mark.parametrize("name,args", [("sync_tms", {}), ("generate_files", {}), ("../sync/execute", {}),
    ("query_inventory", {"force": True}), ("query_inventory", []), ("query_inventory", {"sku": 1})])
def test_forbidden_calls_have_zero_http_requests(name, args):
    with patch.object(main.httpx, "AsyncClient") as transport:
        result = asyncio.run(main.call_tool(name, args, "Bearer synthetic"))
    assert result["error"] in {"TOOL_DENIED", "BAD_TOOL_ARGS"}
    transport.assert_not_called()


def test_rule_queries_do_not_call_model():
    data = {"businessDate": "2026-10-20", "items": []}
    with patch.object(main, "call_tool", new=AsyncMock(return_value=data)) as tool, patch.object(main, "complete", new=AsyncMock()) as model:
        response = request("POST", "/agent/chat", json={"message": "查询库存 DEMO-CUP-01"},
            headers={"X-Service-Key": main.SERVICE_KEY, "Authorization": "Bearer synthetic"})
    assert response.json()["mode"] == "rule"
    assert "不代表库存为零" in response.json()["reply"]
    model.assert_not_called()
    tool.assert_awaited_once_with("query_inventory", {"sku": "DEMO-CUP-01"}, "Bearer synthetic")


def test_missing_model_is_explicit_fallback(monkeypatch):
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    response = request("POST", "/agent/chat", json={"message": "确认同步并执行"},
        headers={"X-Service-Key": main.SERVICE_KEY, "Authorization": "Bearer synthetic"})
    assert response.json()["stopReason"] == "model_not_configured"


def test_multi_tool_protocol_and_tool_evidence():
    calls = [{"id": name, "type": "function", "function": {"name": name, "arguments": "{}"}}
             for name in ["query_inventory", "query_readiness"]]
    captured = []
    async def model(messages):
        captured.append(copy.deepcopy(messages))
        return {"tool_calls": calls} if len(captured) == 1 else {"content": "有依据的解释"}
    with patch.object(main, "complete", side_effect=model), patch.object(main, "call_tool", new=AsyncMock(return_value={"synthetic": True})):
        result = asyncio.run(main.model_chat("请核对库存", "Bearer synthetic"))
    assert result["stopReason"] == "completed" and len(result["evidence"]) == 2
    assert [m["role"] for m in captured[1]] == ["system", "user", "assistant", "tool", "tool"]
    assert len(captured[1][2]["tool_calls"]) == 2


def test_round_limit_preserves_evidence():
    call = {"id": "c", "type": "function", "function": {"name": "query_inventory", "arguments": "{}"}}
    with patch.object(main, "complete", new=AsyncMock(return_value={"tool_calls": [call]})) as model, patch.object(
        main, "call_tool", new=AsyncMock(return_value={"synthetic": True})):
        result = asyncio.run(main.model_chat("调查", "Bearer synthetic"))
    assert model.await_count == 5 and result["stopReason"] == "round_limit"


def test_model_statement_without_tools_is_not_accepted_as_fact():
    with patch.object(main, "complete", new=AsyncMock(return_value={"content": "库存1000，已同步"})):
        result = asyncio.run(main.model_chat("同步了吗", "Bearer synthetic"))
    assert result["stopReason"] == "no_evidence" and "1000" not in result["reply"]


def test_failed_last_attempt_does_not_claim_inventory_failure():
    text = main.describe("query_readiness", {"ready": True, "businessDate": "2026-10-20", "lastAttempt": {"status": "FAILED"}})
    assert "已覆盖" in text and "FAILED" in text and "不表示订单已发货" in text
