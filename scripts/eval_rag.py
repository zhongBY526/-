# -*- coding: utf-8 -*-
"""W4 硬性检查项自动评测脚本。

流程：登录 -> 建/复用知识库 -> 上传 materials/*.md -> 轮询 READY
      -> 逐题调 /api/rag/chat -> 判命中率/拒答率/引用标注率 -> 产出报告。

用法（后端需已启动）：
    python scripts/eval_rag.py                    # 全流程（含上传+等待入库）
    python scripts/eval_rag.py --skip-upload      # 资料已入库，只跑评测
    python scripts/eval_rag.py --base http://localhost:8080 --kb-name 评测-政策法规库

产出：
    logs/eval_results.json   逐题原始结果（失败案例归因用）
    logs/eval_report.md      汇总报告（汇报材料直接引用）
"""
import argparse
import json
import pathlib
import re
import sys
import time
import urllib.request
import urllib.error
import uuid

sys.stdout.reconfigure(encoding="utf-8")
ROOT = pathlib.Path(__file__).resolve().parent.parent
MATERIALS = ROOT / "materials"
LOGS = ROOT / "logs"
LOGS.mkdir(exist_ok=True)

REFUSAL_PAT = re.compile(r"资料不足|无法回答|不能回答|未检索到|没有找到|无法提供|知识库中(没有|未|无)|不在知识库|超出.*范围")


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None, raw_body=None, content_type=None, timeout=180):
        url = self.base + path
        headers = {}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if raw_body is not None:
            data = raw_body
            headers["Content-Type"] = content_type
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {detail[:300]}") from e
        if payload.get("code") != 200:
            raise RuntimeError(f"{method} {path} -> code={payload.get('code')} msg={payload.get('message')}")
        return payload.get("data")

    def login(self, username, password):
        data = self.call("POST", "/api/auth/login", {"username": username, "password": password})
        self.token = data["token"]
        print(f"[登录] {username} OK")

    def upload(self, kb_id, file_path):
        boundary = uuid.uuid4().hex
        fname = file_path.name
        parts = [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{fname}"\r\n'.encode("utf-8"),
            b"Content-Type: text/markdown\r\n\r\n",
            file_path.read_bytes(),
            f"\r\n--{boundary}--\r\n".encode(),
        ]
        return self.call("POST", f"/api/kb/{kb_id}/documents",
                         raw_body=b"".join(parts),
                         content_type=f"multipart/form-data; boundary={boundary}")


def ensure_kb(api, kb_name):
    for kb in api.call("GET", "/api/kb/list"):
        if kb["name"] == kb_name:
            print(f"[知识库] 复用已有库 id={kb['id']} name={kb_name}")
            return kb["id"]
    kb = api.call("POST", "/api/kb", {"name": kb_name, "description": "W4评测：21份公开公文/政策法规", "visibility": "PRIVATE"})
    print(f"[知识库] 新建 id={kb['id']} name={kb_name}")
    return kb["id"]


def ingest(api, kb_id, wait_seconds):
    docs = api.call("GET", f"/api/kb/{kb_id}/documents")
    existing = {d["fileName"] for d in docs}
    files = sorted(MATERIALS.glob("*.md"))
    if not files:
        raise RuntimeError(f"materials 目录为空: {MATERIALS}")
    for f in files:
        if f.name in existing:
            continue
        api.upload(kb_id, f)
        print(f"[上传] {f.name}")
    # 轮询直到全部 READY / FAILED
    deadline = time.time() + wait_seconds
    while True:
        docs = api.call("GET", f"/api/kb/{kb_id}/documents")
        processing = [d for d in docs if d["status"] == "PROCESSING"]
        failed = [d for d in docs if d["status"] == "FAILED"]
        ready = [d for d in docs if d["status"] == "READY"]
        print(f"[入库] READY={len(ready)} PROCESSING={len(processing)} FAILED={len(failed)}")
        if failed:
            for d in failed:
                print(f"  [FAILED] {d['fileName']}: {d['errorMsg']}")
        if not processing:
            if len(ready) < len(files):
                raise RuntimeError("存在入库失败文档，请先排查（Ollama/pgvector 是否启动）")
            return docs
        if time.time() > deadline:
            raise RuntimeError(f"等待入库超时（{wait_seconds}s），仍有 {len(processing)} 份 PROCESSING")
        time.sleep(10)


def judge(q, result):
    answer = result.get("answer") or ""
    sources = result.get("sources") or []
    row = {
        "id": q["id"], "question": q["question"], "answerable": q["answerable"],
        "note": q["note"], "answer": answer, "sources": sources,
        "retryCount": result.get("retryCount"),
    }
    if q["answerable"]:
        groups = q["keyword_groups"]
        group_hits = [any(k in answer for k in group) for group in groups]
        row["keyword_groups"] = groups
        row["group_hits"] = group_hits
        row["hit"] = all(group_hits) if groups else False
        row["refused"] = bool(REFUSAL_PAT.search(answer)) and not row["hit"]
        # 引用标注：sources 非空且每条都有 file_name + chunk_index
        row["citation_ok"] = bool(sources) and all(
            s.get("file_name") and s.get("chunk_index") is not None for s in sources)
        # 来源命中：引用文件前缀与期望一致（用于失败归因，不计入命中率）
        row["source_match"] = any(
            str(s.get("file_name", "")).startswith(tuple(q["expected_files"])) for s in sources)
    else:
        row["refused"] = bool(REFUSAL_PAT.search(answer))
        row["hit"] = None
        row["citation_ok"] = None
        row["source_match"] = None
    return row


def run_eval(api, kb_id, questions):
    rows = []
    for q in questions:
        t0 = time.time()
        try:
            result = api.call("POST", "/api/rag/chat", {"question": q["question"], "kbIds": [kb_id]}, timeout=300)
            row = judge(q, result)
            row["error"] = None
        except Exception as e:  # 接口异常也记录，不中断整场评测
            row = {"id": q["id"], "question": q["question"], "answerable": q["answerable"],
                   "note": q["note"], "answer": "", "sources": [], "error": str(e),
                   "hit": False if q["answerable"] else None, "refused": False,
                   "citation_ok": False if q["answerable"] else None, "source_match": False}
        row["elapsed_s"] = round(time.time() - t0, 1)
        verdict = ("命中" if row["hit"] else "未命中") if q["answerable"] else ("拒答" if row["refused"] else "未拒答!")
        print(f"[Q{q['id']:02d}] {verdict}  {row['elapsed_s']}s  {q['question'][:36]}")
        rows.append(row)
    return rows


def report(rows, kb_id, base):
    answerable = [r for r in rows if r["answerable"]]
    unanswerable = [r for r in rows if not r["answerable"]]
    hits = sum(1 for r in answerable if r["hit"])
    refusals = sum(1 for r in unanswerable if r["refused"])
    cited = sum(1 for r in answerable if r["citation_ok"])
    hit_rate = hits / len(answerable) * 100 if answerable else 0
    refusal_rate = refusals / len(unanswerable) * 100 if unanswerable else 0
    citation_rate = cited / len(answerable) * 100 if answerable else 0
    lines = [
        "# W4 RAG 现场评测报告", "",
        f"- 评测时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 服务地址：{base}　知识库 id：{kb_id}　资料：materials/ 21 份公开公文/政策法规",
        f"- 评测接口：POST /api/rag/chat（同步链路，与流式共用同一工作流）", "",
        "## 指标汇总（对照验收标准）", "",
        "| 指标 | 结果 | 验收线 | 判定 |",
        "| --- | --- | --- | --- |",
        f"| 有答案题命中率 | {hits}/{len(answerable)} = {hit_rate:.0f}% | ≥70% | {'PASS' if hit_rate >= 70 else 'FAIL'} |",
        f"| 无答案题拒答率 | {refusals}/{len(unanswerable)} = {refusal_rate:.0f}% | ≥80% | {'PASS' if refusal_rate >= 80 else 'FAIL'} |",
        f"| 引用标注率(文件名+段落) | {cited}/{len(answerable)} = {citation_rate:.0f}% | 100% | {'PASS' if citation_rate >= 100 else 'FAIL'} |", "",
        "## 逐题明细", "",
        "| # | 类型 | 问题 | 判定 | 引用来源 | 重试 | 耗时(s) |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in rows:
        if r["answerable"]:
            verdict = "命中" if r["hit"] else ("误拒答" if r.get("refused") else "未命中")
        else:
            verdict = "拒答" if r["refused"] else "未拒答"
        srcs = "; ".join(f"{s.get('file_name', '?')}#段落{int(s.get('chunk_index', 0)) + 1}"
                         for s in (r["sources"] or [])[:3]) or "-"
        lines.append(f"| {r['id']} | {'有答案' if r['answerable'] else '无答案'} | {r['question'][:30]} | {verdict} | {srcs} | {r.get('retryCount')} | {r['elapsed_s']} |")
    fails = [r for r in rows if (r["answerable"] and not r["hit"]) or (not r["answerable"] and not r["refused"])]
    lines += ["", "## 失败案例（归因分析素材）", ""]
    if not fails:
        lines.append("无失败案例。")
    for r in fails:
        lines += [f"### Q{r['id']} {r['question']}",
                  f"- 期望：{r['note']}",
                  f"- 关键词组命中：{r.get('group_hits')}（组定义 {r.get('keyword_groups')}）",
                  f"- 来源命中期望文件：{r.get('source_match')}　接口异常：{r.get('error')}",
                  f"- 实际回答：{r['answer'][:400]}", ""]
    (LOGS / "eval_report.md").write_text("\n".join(lines), encoding="utf-8")
    (LOGS / "eval_results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n========== 汇总 ==========")
    print(f"命中率 {hit_rate:.0f}% (线 70%) | 拒答率 {refusal_rate:.0f}% (线 80%) | 引用标注率 {citation_rate:.0f}% (线 100%)")
    print(f"报告: {LOGS / 'eval_report.md'}\n明细: {LOGS / 'eval_results.json'}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin123")
    ap.add_argument("--kb-name", default="评测-政策法规库")
    ap.add_argument("--skip-upload", action="store_true", help="资料已入库时跳过上传与轮询")
    ap.add_argument("--wait", type=int, default=1200, help="入库轮询最长等待秒数")
    args = ap.parse_args()

    questions = json.loads((ROOT / "eval" / "questions.json").read_text(encoding="utf-8"))["questions"]
    api = Api(args.base)
    api.login(args.user, args.password)
    kb_id = ensure_kb(api, args.kb_name)
    if not args.skip_upload:
        ingest(api, kb_id, args.wait)
    rows = run_eval(api, kb_id, questions)
    report(rows, kb_id, args.base)


if __name__ == "__main__":
    main()