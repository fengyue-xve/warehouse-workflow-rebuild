import asyncio
from unittest.mock import AsyncMock, patch
import httpx
import pytest
from app import main
from tests.test_boundary import request

CARD = {"kind":"sync_preview","canConfirm":True,"scenario":"normal","preview":{"sourceDigest":"a"*64,"previewToken":"synthetic-operator-bound-credential","changes":[{"sku":"DEMO-CUP-01","before":80,"after":100}],"expiresInSeconds":300}}

@pytest.mark.parametrize('message',['执行同步','更新库存','确认同步并执行','重跑库存','同步库存'])
def test_sensitive_intent_previews_outside_model_loop(message):
    with patch.object(main,'sensitive_preview',new=AsyncMock(return_value={"mode":"intercepted","previewCard":CARD,"routing":main.routing()})) as preview, patch.object(main,'complete',new=AsyncMock()) as model:
        response=request('POST','/agent/chat',json={'message':message},headers={'X-Service-Key':main.SERVICE_KEY,'Authorization':'Bearer synthetic'})
    assert response.status_code==200 and response.json()['previewCard']==CARD
    assert response.json()['routing']['tokenUsage']==0
    preview.assert_awaited_once_with('Bearer synthetic'); model.assert_not_called()


def test_preview_uses_only_fixed_read_route_and_never_execution_or_model_context():
    calls=[]
    def handler(request):
        calls.append(request)
        return httpx.Response(200,json=CARD)
    async def run():
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with patch.object(main.httpx,'AsyncClient',return_value=client),patch.object(main,'complete',new=AsyncMock()) as model:
            result=await main.sensitive_preview('Bearer synthetic')
        model.assert_not_called()
        return result
    result=asyncio.run(run())
    assert result['previewCard']==CARD and len(calls)==1
    assert calls[0].url.path=='/api/sync/preview-read' and calls[0].headers['authorization']=='Bearer synthetic'
    assert calls[0].content==b'{}'


def test_preview_failure_is_visible_and_does_not_reach_model():
    def handler(request): return httpx.Response(503)
    async def run():
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with patch.object(main.httpx,'AsyncClient',return_value=client): return await main.sensitive_preview('Bearer synthetic')
    result=asyncio.run(run())
    assert result['stopReason']=='preview_unavailable' and 'previewCard' not in result


def test_fixed_rule_reports_zero_model_calls():
    with patch.object(main,'call_tool',new=AsyncMock(return_value={'items':[],'businessDate':'2026-10-20'})):
        result=request('POST','/agent/chat',json={'message':'查询库存'},headers={'X-Service-Key':main.SERVICE_KEY,'Authorization':'Bearer synthetic'}).json()
    assert result['routing']=={'modelCalls':0,'maxRounds':5,'tokenUsage':0}
