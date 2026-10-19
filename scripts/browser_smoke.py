"""可选浏览器验收，操作的是指定的本机合成演示；需要 playwright 与 Chromium。"""
import json
from pathlib import Path
import re
import sys

from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18080"
if not re.fullmatch(r"http://127\.0\.0\.1:\d+", base):
    raise SystemExit("只接受本机演示地址")
checks = []
errors = []
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 1100}, device_scale_factor=1)
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(base)
    page.get_by_role("button", name="进入工作台").click()
    expect(page.get_by_role("heading", name="库存核对", exact=True)).to_be_visible()
    checks.append("login and inventory render")
    # 已有任务时从最新终态刷新；新数据库则使用普通任务。
    refresh_buttons = page.get_by_role("button", name="人工刷新", exact=True)
    if refresh_buttons.count():
        refresh_buttons.first.click()
    page.get_by_label("导出场景").select_option("empty")
    page.get_by_role("button", name="预检来源与变更").click()
    expect(page.get_by_role("heading", name="发现问题：业务表将保持原状")).to_be_visible()
    expect(page.get_by_role("button", name="运行拒写场景并记录")).to_be_disabled()
    checks.append("preview blocks empty source and requires explicit checkbox")
    page.get_by_role("checkbox").check()
    page.get_by_role("button", name="运行拒写场景并记录").click()
    expect(page.get_by_role("status")).to_contain_text("失败，需核对")
    page.get_by_role("button", name="核对后重试", exact=True).first.click()
    page.get_by_role("button", name="预检来源与变更").click()
    expect(page.get_by_role("heading", name="预检通过，等待你确认")).to_be_visible()
    page.get_by_role("checkbox").check()
    page.get_by_role("button", name="确认执行", exact=True).click()
    expect(page.get_by_role("status")).to_contain_text("任务已完成")
    checks.append("failed task can be manually retried through UI")
    expect(page.get_by_role("row").filter(has_text="DEMO-CUP-01")).to_contain_text("100")
    page.reload()
    expect(page.get_by_role("heading", name="库存核对", exact=True)).to_be_visible()
    checks.append("session and persisted stock survive page refresh")
    page.get_by_role("button", name=re.compile("只读查询助手")).click()
    page.get_by_role("button", name="查询", exact=True).click()
    expect(page.locator(".message.assistant").last).to_contain_text("规则查询")
    expect(page.locator(".message.assistant").last).to_contain_text("合成演示")
    checks.append("assistant displays real rule-tool result")
    page.get_by_role("button", name=re.compile("库存同步与核对")).click()
    shots = ROOT / "docs/images"; shots.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(shots / "workbench.png"), full_page=True)
    page.set_viewport_size({"width": 390, "height": 844})
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "mobile horizontal overflow"
    page.screenshot(path=str(shots / "workbench-mobile.png"), full_page=True)
    checks.append("mobile layout has no page overflow")
    page.get_by_role("button", name="退出", exact=True).click()
    page.get_by_label("演示身份").select_option("viewer")
    page.get_by_role("button", name="进入工作台").click()
    expect(page.get_by_text("viewer 可以查询数据与任务；请使用 operator 身份体验预检与确认。")).to_be_visible()
    expect(page.get_by_role("button", name="预检来源与变更")).to_have_count(0)
    checks.append("viewer UI exposes no execution controls")
    assert not errors, errors
    checks.append("no uncaught browser errors")
    browser.close()
output = ROOT / "artifacts"; output.mkdir(exist_ok=True)
result = {"status": "passed", "checks": checks, "count": len(checks)}
(output / "browser-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
