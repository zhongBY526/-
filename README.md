# 企业知识库 RAG + 公文工作流 Lab（W2–W4）

Vue3 + Spring Boot 3 + LangChain4j + LangGraph4j + pgvector + Ollama `bge-m3` + DeepSeek 的全栈练习项目。

覆盖三条主线：

- **W2 流式闭环**：Vue 页面 → Spring Boot SSE → 大模型流式返回，附公文要素抽取。
- **W3 知识库 RAG**：21 份公开公文/政策资料入库，混合检索 + rerank + 引用溯源 + 无答案拒答，15 题评测。
- **W4 公文工作流**：意图识别 → 检索 → 生成 → 格式校验 Skill → 人工确认 → 导出；含 2 个工具、条件分支、状态机与异常兜底。

## 架构

```text
浏览器 (5173)
├─ /login      POST /api/auth/login            → Bearer Token（内存 TokenStore）
├─ /kb         知识库 CRUD + 文档上传/状态查询
├─ /kb/chat    POST /api/rag/chat[/stream]     → RagWorkflow
│                向量检索 + 关键词检索 → RRF 融合 → DashScope rerank
│                → DeepSeek 生成 → 引用（文件名#段落）/ 无答案拒答
├─ /workflow   POST /api/workflow/tasks        → DocWorkflow（LangGraph4j 6 节点）
│                classify → retrieve → generate → formatCheck → 人工确认 → 导出
│                STATS / CHAT 分支走 KbStatsTool / HttpTimeTool
└─ /chat /extract                              → W2 流式对话、公文要素抽取

外部依赖：pgvector(5432) + Ollama bge-m3(11434) + DeepSeek API + 可选 DashScope rerank
```

## 目录结构

```text
.
├─ backed/                 Spring Boot 后端
│  ├─ src/main/java/com/intern/backed/
│  │  ├─ auth/             登录、Token、拦截器、种子账号
│  │  ├─ kb/ document/     知识库、文档上传、解析切分、入库
│  │  ├─ retrieval/        混合检索、RRF、rerank、引用块
│  │  ├─ graph/            RagWorkflow（检索/生成/拒答）
│  │  ├─ workflow/         DocWorkflow、任务状态机、工具、Skill
│  │  ├─ config/           LangChain4j 模型、异步线程池、Web 配置
│  │  └─ controller/       RAG、抽取、公开时间接口
│  └─ src/main/resources/
│     ├─ application.yaml  端口/模型/检索/rerank 配置
│     ├─ db/init.sql       业务表初始化（pgvector 容器首次启动执行）
│     └─ messagePrompt.md  W2 对话提示词（含 {{style}} 变量）
├─ front/                  Vue 3 + Vite
│  └─ src/views/           Login / KbManage / KbChat / Workflow / Chat / Extract
├─ docs/                   学习导读、RAG/工作流实施指南、Skill 设计、W4 交付清单
├─ eval/questions.json     15 题评测集（10 有答案 + 5 无答案）
├─ materials/              21 份评测资料（每份头部含来源 URL）
├─ scripts/                eval_rag.py、verify_workflow.py、clean_materials.py、peek_task.py
├─ logs/                   运行日志与评测/验证产物
├─ docker-compose.yml      pgvector/pg17（首次启动执行 init.sql）
├─ start.sh / start.ps1    一键启动后端 + 前端（需先启动 pgvector 与 Ollama）
└─ README.md
```

## 环境要求

| 依赖 | 版本 / 说明 |
| --- | --- |
| JDK | 17 |
| Node.js | 22.18+ 或 24.12+（见 `front/package.json` engines） |
| Docker Desktop | 用于 `pgvector/pgvector:pg17` |
| Ollama | 本地 Embedding，模型 `bge-m3`（1024 维） |
| 大模型 Key | `DEEPSEEK_API_KEY`（对话/生成） |
| Rerank（可选） | `QWEN_API_KEY` + `RERANK_BASE_URL` + `RERANK_MODEL`；不配则跳过 rerank，降级为 RRF 排序 |
| 端口 | 8080（后端）、5173（前端）、5432（pgvector）、11434（Ollama） |

## 快速启动

### 1. 启动 pgvector

```bash
docker compose up -d
docker ps --filter name=rag-pgvector
```

首次创建数据卷时会执行 `backed/src/main/resources/db/init.sql`，建 `users / knowledge_base / rag_document / workflow_task` 表；向量表 `rag_embedding` 由 PgVectorEmbeddingStore 自建。已有数据卷不会重复执行 `init.sql`，改表结构需手动执行变更 SQL。

### 2. 启动 Ollama 并准备 Embedding 模型

```bash
ollama serve
ollama pull bge-m3
curl http://localhost:11434/api/tags
```

### 3. 启动后端

```bash
cd backed
cp .env.example .env
# 编辑 .env，至少填 DEEPSEEK_API_KEY；可选 QWEN_API_KEY / RERANK_*
./mvnw spring-boot:run          # PowerShell: .\mvnw.cmd spring-boot:run
```

看到 `Tomcat started on port 8080` 即成功。**必须在 `backed/` 目录下启动**，`.env` 按当前工作目录导入。

### 4. 启动前端

```bash
cd front
npm install
npm run dev
```

打开 <http://localhost:5173>，登录账号：

- `admin / admin123`（ADMIN）
- `demo / demo123`（USER，技术部）

种子账号仅用于本地学习/演示，生产环境应删除或强制首登改密。

### 5. 一键启动（可选）

```bash
./start.sh          # Git Bash / macOS / Linux
```

```powershell
.\start.ps1         # Windows PowerShell
```

脚本只负责拉起**后端 + 前端**，并检查 `.env`、端口、Ollama/pgvector 是否就绪；数据库与 Ollama 需先按上面第 1、2 步启动。日志写入 `logs/backend.log`、`logs/front.log`。

## 页面

| 路径 | 功能 |
| --- | --- |
| `/login` | 登录，Token 存 localStorage |
| `/kb` | 知识库列表、创建/删除、文档上传与状态 |
| `/kb/chat` | 知识库问答，展示 `[n]` 引用、文件名#段落、拒答 |
| `/workflow` | 公文工作流：流程可视化、2s 轮询、确认/驳回/重跑、Skill 报告、导出 Markdown |
| `/chat` | W2 流式对话（风格切换、首字计时、traceId） |
| `/extract` | W2 公文要素抽取（JSON 兜底 warnings） |

## W3：知识库 RAG

### 能力

- 文档解析：`pdf`（PDFBox）、`doc/docx`（POI）、`txt/md`；上传上限 20MB。
- 切分：LangChain4j `recursive`，`chunk-size=500`、`chunk-overlap=50`，每段带 `kb_id/doc_id/file_name/chunk_index`。
- Embedding：Ollama `bge-m3`，维度 1024；向量写入 pgvector `rag_embedding`。
- 检索：向量 top20 + 关键词 top20 → RRF 融合 → DashScope rerank → top5；`min-score=0.3`；最多 2 次检索重试。
- 生成：只依据检索片段回答；检索为空直接确定性拒答；重试耗尽仍不足则拒答。
- 引用：答案带 `[n]`，前端展示 `文件名#段落`。

### 接口

```http
POST /api/rag/chat
Authorization: Bearer <token>
Content-Type: application/json

{"question": "医疗器械经营许可证的有效期是多久？", "kbIds": [5]}
```

`kbIds` 可省略，缺省为当前用户可访问的全部知识库。流式接口为 `POST /api/rag/chat/stream`（SSE）。

知识库与文档：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/kb/list` | 当前用户可访问知识库 |
| POST | `/api/kb` | 创建 `{name, description, visibility}` |
| PUT/DELETE | `/api/kb/{kbId}` | 更新/删除 |
| POST | `/api/kb/{kbId}/documents` | multipart 字段名 `file` |
| GET | `/api/kb/{kbId}/documents` | 文档状态 `PROCESSING/READY/FAILED` |
| DELETE | `/api/kb/documents/{docId}` | 删除文档及向量 |

## W4：公文工作流

### 图与分支

```text
START → classify(意图识别)
classify --条件边--> retrieve(QA/DRAFT) | stats(STATS) | chat(CHAT)
retrieve → generate → formatCheck(公文格式校验 Skill) → END
stats/chat → END
```

- 4 类意图：`QA / DRAFT / STATS / CHAT`；分类失败兜底 `QA`。
- 2 个工具：`KbStatsTool`（数据库聚合知识库/文档/分块数）、`HttpTimeTool`（HTTP 调 `/api/public/now`，3s 超时 + 1 次重试）。
- 状态机：`RUNNING → PENDING_CONFIRM → CONFIRMED / REJECTED → EXPORTED`；异常进 `FAILED`；驳回/失败可重跑。
- 人工确认：只有 `CONFIRMED` 能导出；未确认导出返回 409。
- Skill：`OfficialDocFormatSkill` 8 条规则（R1~R8），`ERROR×20 / WARN×8` 扣分，输出结构化报告。

### 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/workflow/tasks` | `{question, kbIds?}`，立即返回 RUNNING，图异步执行 |
| GET | `/api/workflow/tasks` | 当前用户任务列表 |
| GET | `/api/workflow/tasks/{id}` | 状态、意图、步骤轨迹、草稿、报告、引用 |
| POST | `/api/workflow/tasks/{id}/confirm` | `{action: "CONFIRM"|"REJECT", comment?}` |
| POST | `/api/workflow/tasks/{id}/retry` | 驳回/失败后重跑 |
| GET | `/api/workflow/tasks/{id}/export` | 导出 Markdown（仅 CONFIRMED） |

前端 `/workflow` 用 2s 轮询任务状态（不是 SSE），刷新页面后任务仍可从数据库恢复。

## 评测与验证

先确保后端、pgvector、Ollama 已启动。

```bash
# 全量：登录 → 查找/创建“评测-政策法规库” → 上传 21 份资料 → 轮询入库 → 15 题问答 → 出报告
python scripts/eval_rag.py

# 资料已入库时只跑问答
python scripts/eval_rag.py --skip-upload

# 工作流端到端验证：登录 → 四类意图 → 确认/驳回/重跑 → 导出 → 409
python scripts/verify_workflow.py
```

产物：

- `logs/eval_report.md`：指标汇总 + 逐题引用/重试/耗时。
- `logs/eval_results.json`：逐题完整答案与引用原文。
- `logs/workflow_verify.json`：32 项验证明细。

基线（2026-11-26 16:13 复跑）：

| 指标 | 结果 | 验收线 |
| --- | --- | --- |
| 有答案题命中率 | 10/10 = 100% | ≥70% |
| 无答案题拒答率 | 5/5 = 100% | ≥80% |
| 引用标注率（文件名+段落） | 10/10 = 100% | 100% |
| 有答案题平均耗时 | 5.3s | — |
| 无答案题平均耗时 | 10.8s（2 次重试） | — |
| 工作流验证 | 32/32 PASS | 全部通过 |

评测判定是关键词组命中制（同义词任一命中），不是人工语义评分；现场建议抽 2 题人工核对答案原文。

## W2 历史功能

- `POST /api/chat/stream`：SSE 事件 `meta / token / done / error`，`conversationId` 作为 ChatMemory 键。
- `POST /api/extract`：公文要素抽取（文种/标题/主送/落款/日期），模型输出非 JSON 时降级为 `warnings`。
- 风格变量：`backed/src/main/resources/messagePrompt.md` 的 `{{style}}` 由请求参数注入（严谨/温和/简洁）。
- 离线单测：`cd backed && ./mvnw test`。

## 配置说明

`.env` 样例见 `backed/.env.example`，常用项：

```ini
DEEPSEEK_API_KEY=sk-your-key
# DEEPSEEK_BASE_URL=https://api.deepseek.com
# DEEPSEEK_MODEL=deepseek-chat

# 可选：DashScope rerank；不配则自动跳过
# QWEN_API_KEY=sk-your-dashscope-key
# RERANK_BASE_URL=https://dashscope.aliyuncs.com
# RERANK_MODEL=gte-rerank-v2

# 可选：PostgreSQL/pgvector（默认与 docker-compose.yml 一致）
# PG_HOST=localhost
# PG_PORT=5432
# PG_DATABASE=ragdb
# PG_USER=rag
# PG_PASSWORD=rag

# 可选：Ollama Embedding
# OLLAMA_BASE_URL=http://localhost:11434
# OLLAMA_EMBEDDING_MODEL=bge-m3
```

`application.yaml` 中检索参数：`vector-top-k=20`、`keyword-top-k=20`、`final-top-k=5`、`min-score=0.3`、`retry.max=2`；切分参数 `chunk-size=500`、`chunk-overlap=50`。

rerank 使用 DashScope 原生协议：`base-url` 只到域名，末尾不要带 `/compatible-mode/v1`；用专属部署时填对应 workspace 地址。

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `Could not resolve placeholder 'DEEPSEEK_API_KEY'` | `.env` 不存在，或没在 `backed/` 目录启动 |
| 后端启动报数据库连接失败 | pgvector 未启动：`docker compose up -d`；`docker ps` 看 `rag-pgvector` 是否 healthy |
| 上传一直 `PROCESSING` / 评测提示入库失败 | Ollama 未启动或没有 `bge-m3`：`ollama serve`、`ollama pull bge-m3` |
| rerank 401/404 | `QWEN_API_KEY`/`RERANK_BASE_URL`/`RERANK_MODEL` 不匹配；可先留空，降级 RRF |
| 所有接口 401 | Token 存在内存，后端重启后需重新登录 |
| 上传返回 400 `file` 相关 | multipart 字段名必须是 `file` |
| 上传 413 | 单文件超过 20MB（`multipart.max-file-size`） |
| 导出返回 409 | 任务还没人工确认，先 `CONFIRM` 再导出 |
| 无答案题拒答但仍显示引用 | 已知问题：低分引用未在展示层过滤，列入 W5 优化 |
| 拒答约 10s | 触发了 2 次检索重试；W5 计划用 rerank 阈值直接拒答，目标 ≤5s |
| 页面一次性整段出现 | 中间层开了响应缓冲；后端已设 `X-Accel-Buffering: no`，Nginx 需关 `proxy_buffering` |
| 端口被占用 | 改 `application.yaml` 的 `server.port` 或 `front/vite.config.js` 的代理/端口 |

## 安全与已知边界

- 密钥只放 `backed/.env`，已在 `.gitignore`；提交前 `git status` 与 `git ls-files | grep -i env` 检查。
- 登录态存在内存 `TokenStore`，重启即失效；未接 JWT/Redis，W5 再工程化。
- Skill 是纯规则引擎，只校验格式不校验内容事实性；内容正确性靠引用溯源 + 人工确认。
- 导出为 Markdown（含引用附录），未做 docx 红头排版。
- 工作流进度用 2s 轮询；SSE 进度推送作为后续优化项。
- 评测资料全部为公开政府网站文本，仅用于学习与评测。

## 学习文档

- [项目学习导读](docs/项目学习导读.md)
- [企业知识库 RAG 实现清单与步骤](docs/企业知识库RAG-实现清单与步骤.md)
- [RAG 流式输出前后端实施指南](docs/RAG流式输出-前后端实施指南.md)
- [RAG + LangGraph4j 实施指南](docs/RAG-LangGraph4j-实施指南.md)
- [W4 学习文档：公文工作流与 RAG 评测](docs/W4学习文档-公文工作流与RAG评测.md)
- [Skill 设计文档：公文格式校验](docs/Skill设计文档-公文格式校验.md)
- [W4 交付清单与汇报要点](docs/W4-交付清单与汇报要点.md)
- [面试教练 Agent 搭建清单（W5 预备）](docs/面试教练Agent-搭建清单.md)