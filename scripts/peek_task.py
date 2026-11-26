# -*- coding: utf-8 -*-
"""查看指定工作流任务的 Skill 格式校验报告明细。用法: python scripts/peek_task.py [taskId]"""
import json
import pathlib
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
BASE = "http://localhost:8080"
task_id = sys.argv[1] if len(sys.argv) > 1 else "6"


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


token = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})["data"]["token"]
task = call("GET", f"/api/workflow/tasks/{task_id}", token=token)["data"]
report = json.loads(task["formatReport"] or "{}")
print(f"任务 #{task_id}  status={task['status']}  intent={task['intent']}")
print(f"Skill 得分: {report.get('score')}  passed={report.get('passed')}")
for item in report.get("items", []):
    mark = "✓" if item["passed"] else item["severity"]
    print(f"  [{mark}] {item['rule']} {item['name']}: {item['message']}")
steps = json.loads(task["steps"] or "[]")
print("节点轨迹:", " → ".join(f"{s['node']}({s['status']})" for s in steps))