# -*- coding: utf-8 -*-
"""工作流全链路 API 验证：触发→轮询→人工确认(通过/驳回)→重跑→导出 + 异常路径(401/409)。

用法：
    python scripts/verify_workflow.py            # 全部分支（QA/DRAFT 需资料已入库）
    python scripts/verify_workflow.py --quick    # 只验 CHAT/STATS + 状态机异常路径
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.request
import urllib.error

sys.stdout.reconfigure(encoding="utf-8")
ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = "http://localhost:8080"
TOKEN = None
RESULTS = []


def call(method, path, body=None, expect_code=200, timeout=60, auth=True):
    headers = {"Content-Type": "application/json"}
    if auth and TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}  {detail}")


def wait_done(task_id, max_wait=240):
    deadline = time.time() + max_wait
    while time.time() < deadline:
        code, raw = call("GET", f"/api/workflow/tasks/{task_id}")
        task = json.loads(raw)["data"]
        if task["status"] != "RUNNING":
            return task
        time.sleep(3)
    raise TimeoutError(f"task {task_id} 超时仍 RUNNING")


def run_branch(question, expect_intent, confirm_action="CONFIRM", kb_ids=None):
    body = {"question": question}
    if kb_ids:
        body["kbIds"] = kb_ids
    code, raw = call("POST", "/api/workflow/tasks", body)
    task = json.loads(raw)["data"]
    check(f"触发({expect_intent})", code == 200 and task["status"] == "RUNNING", f"id={task['id']}")
    task = wait_done(task["id"])
    check(f"执行完成({expect_intent})", task["status"] in ("PENDING_CONFIRM", "FAILED"),
          f"status={task['status']} intent={task['intent']}")
    check(f"意图识别({expect_intent})", task["intent"] == expect_intent, f"实际={task['intent']}")
    steps = json.loads(task["steps"] or "[]")
    check(f"节点轨迹({expect_intent})", len(steps) >= 2, " → ".join(s["node"] for s in steps))
    return task


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    args = ap.parse_args()

    # 登录
    code, raw = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"}, auth=False)
    TOKEN = json.loads(raw)["data"]["token"]
    check("登录", code == 200)

    # 未认证 401
    code, _ = call("GET", "/api/workflow/tasks", auth=False)
    check("未认证访问被拦截(401)", code == 401)

    # CHAT 分支：HttpTimeTool
    t = run_branch("你好，现在几点了？", "CHAT")
    check("HTTP时间工具调用", "服务器时间" in t["draft"] or "兜底" in t["draft"], t["draft"][:60])

    # 未确认先导出 → 409
    code, raw = call("GET", f"/api/workflow/tasks/{t['id']}/export")
    check("未确认导出被拒(409)", code == 409, json.loads(raw).get("message", ""))

    # 人工确认通过 → 导出 → EXPORTED → 重复下载
    code, raw = call("POST", f"/api/workflow/tasks/{t['id']}/confirm", {"action": "CONFIRM", "comment": "验证通过"})
    check("人工确认CONFIRM", code == 200 and json.loads(raw)["data"]["status"] == "CONFIRMED")
    code, raw = call("GET", f"/api/workflow/tasks/{t['id']}/export")
    check("导出Markdown", code == 200 and len(raw) > 0, f"{len(raw)} bytes")
    (ROOT / "logs" / f"workflow-export-{t['id']}.md").write_bytes(raw)
    code, _ = call("GET", f"/api/workflow/tasks/{t['id']}/export")
    check("EXPORTED重复下载", code == 200)

    # STATS 分支：KbStatsTool
    t2 = run_branch("统计一下知识库的文档入库情况", "STATS")
    check("DB统计工具输出", "知识库统计" in t2["draft"], t2["draft"][:60])
    # 驳回 → 重跑 → 再确认（人工确认可回退）
    code, raw = call("POST", f"/api/workflow/tasks/{t2['id']}/confirm", {"action": "REJECT", "comment": "验证驳回回退"})
    check("人工驳回REJECT", code == 200 and json.loads(raw)["data"]["status"] == "REJECTED")
    code, raw = call("POST", f"/api/workflow/tasks/{t2['id']}/retry")
    check("驳回后重跑", code == 200 and json.loads(raw)["data"]["status"] == "RUNNING")
    t2 = wait_done(t2["id"])
    check("重跑完成", t2["status"] == "PENDING_CONFIRM")
    code, raw = call("POST", f"/api/workflow/tasks/{t2['id']}/confirm", {"action": "CONFIRM", "comment": "重跑后通过"})
    check("重跑后确认", code == 200)

    if not args.quick:
        # QA 分支：无答案问题必须拒答（异常兜底）
        t3 = run_branch("我们公司员工年假的审批流程是什么？", "QA")
        refused = any(k in t3["draft"] for k in ("资料不足", "无法回答", "未检索到"))
        check("QA无答案拒答兜底", refused, t3["draft"][:80])
        # DRAFT 分支：起草公文 + Skill 校验
        t4 = run_branch("根据医疗器械监督管理条例，起草一份关于开展医疗器械经营合规自查的通知", "DRAFT")
        report = json.loads(t4["formatReport"] or "{}")
        check("Skill格式校验报告", "items" in report and len(report.get("items", [])) == 8,
              f"score={report.get('score')} passed={report.get('passed')}")
        sources = json.loads(t4["sources"] or "[]")
        check("DRAFT引用来源(文件名+段落)", bool(sources) and all(
            s.get("file_name") and s.get("chunk_index") is not None for s in sources),
            f"{len(sources)} 条")
        code, raw = call("POST", f"/api/workflow/tasks/{t4['id']}/confirm", {"action": "CONFIRM", "comment": "公文核对无误"})
        code, raw = call("GET", f"/api/workflow/tasks/{t4['id']}/export")
        check("DRAFT导出", code == 200)
        (ROOT / "logs" / f"workflow-export-{t4['id']}.md").write_bytes(raw)

    failed = [r for r in RESULTS if not r[1]]
    print(f"\n========== {len(RESULTS) - len(failed)}/{len(RESULTS)} PASS ==========")
    out = [{"name": n, "pass": ok, "detail": d} for n, ok, d in RESULTS]
    (ROOT / "logs" / "workflow_verify.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()