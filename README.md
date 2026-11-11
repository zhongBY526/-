# W2 流式对话 Lab

Vue3 + Spring Boot 3 + LangChain4j + DeepSeek 的最小全栈闭环：**Vue 页面 → Spring Boot 接口 → 大模型流式返回**，附带一个公文要素抽取接口。

```
浏览器 (5173)
  └─ POST /api/chat/stream  ──►  Spring Boot (8080)  ──►  DeepSeek / vLLM
        ▲                              │
        └──── SSE: meta / token / done / error ────┘
```

## 目录结构

```
.
├── backed/                 # Spring Boot 后端
│   ├── .env.example        # 环境变量样例（复制成 .env 使用）
│   └── src/main/
│       ├── java/com/intern/backed/
│       │   ├── chat/       # /api/chat/stream（SSE）、/api/extract
│       │   ├── config/     # LangChain4j AiService、ChatMemory、JSON 模型
│       │   ├── dto/        # 请求/响应对象
│       │   ├── service/    # 公文抽取的解析与兜底
│       │   └── common/     # 统一响应、全局异常处理
│       └── resources/
│           ├── application.yaml    # 端口、模型、.env 导入
│           ├── messagePrompt.md    # 对话系统提示词（含 {{style}} 变量）
│           └── extract-prompt.md   # 抽取提示词
└── front/                  # Vue 3 + Vite 前端
    ├── vite.config.js      # /api 代理到 localhost:8080
    └── src/views/          # ChatViews.vue（流式对话）、ExtractView.vue（抽取）
```

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | `java -version` 确认 |
| Node.js | 22.18+ 或 24.12+ | `front/package.json` 的 engines |
| 网络 | 可访问 `api.deepseek.com` | 首次运行 mvnw 还会下载 Maven 3.10 |

## 快速启动

### 1. 后端

```bash
cd backed
cp .env.example .env          # PowerShell: Copy-Item .env.example .env
# 编辑 .env，把 sk-your-key 换成真实 Key
./mvnw spring-boot:run        # PowerShell: .\mvnw.cmd spring-boot:run
```

看到 `Tomcat started on port 8080` 即成功。**必须在 `backed/` 目录下启动**，因为 `.env` 是按当前工作目录导入的。

### 2. 前端（另开一个终端）

```bash
cd front
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>。

### 3. 验证

1. 页面选「流式对话」，输入问题，回车；
2. 文字应逐字出现，底部显示「前端首字 / 后端首字 / 结束原因」；
3. 切到「公文抽取」，点「填充示例」再点「抽取」，应返回结构化字段。

## 现场演示：改 Prompt 变量

系统提示词在 `backed/src/main/resources/messagePrompt.md`，第一段的 `{{style}}` 由接口参数 `style` 注入。

- **方式 A（不改代码、立即生效）**：页面上切换「风格：严谨 / 温和 / 简洁」。同一个问题分别问一次，对比措辞与篇幅，能说清变量注入了哪一句。
- **方式 B（改模板）**：直接改 `messagePrompt.md` 里的 `{{style}}` 所在句，或第 8 节「普通问题尽量控制在 100---300 字」。**改完要重启后端**（资源在启动时加载）。
- **方式 C（换模型）**：在 `backed/.env` 里设 `DEEPSEEK_MODEL` 后重启，可对比不同模型的回答效果与「后端首字」。

## 接口

### `POST /api/chat/stream`

请求体：

```json
{
  "message": "失业保险金怎么申领",
  "style": "严谨",
  "conversationId": "可选，缺省由服务端生成"
}
```

响应为 `text/event-stream`，事件顺序固定：

| event | data | 说明 |
| --- | --- | --- |
| `meta` | `{messageId, conversationId}` | 流开始，返回会话 ID |
| `token` | `{delta}` | 增量文本，逐条拼接即为完整回答 |
| `done` | `{finishReason, firstTokenMs, usage}` | 正常结束；`finishReason=length` 表示被截断 |
| `error` | `{code, message, traceId}` | 模型侧异常，前端应提示并保留 traceId |

`conversationId` 会作为 ChatMemory 的键，同一会话保留最近 10 条消息；换新会话请用页面上的「新会话」按钮。参数校验失败（如 `style` 非法）返回 JSON：`{code: 400, message, data: null, traceId}`。

### `POST /api/extract`

请求体 `{"text": "公文全文"}`，返回 `{code, message, data, traceId}`，`data` 含 `docType / title / addressee / signature / date / warnings`。模型输出不是合法 JSON 或字段越界时，会降级返回可人工核对的 `warnings`，不会抛 500。

## W2 验收标准对照（G5）

| 标准 | 实现 | 现场怎么证明 |
| --- | --- | --- |
| 首字延迟可感知 | 页面「首字」先显示 `等待中 x.x s`，收到第一个 token 后锁定耗时；≤3s 绿色、>3s 红色；同时显示「后端首字」区分浏览器/网络与模型耗时 | 发一句话，观察计时与颜色；后端日志有 `chat first token, traceId=..., firstTokenMs=...` |
| 流式无截断 | 每个 token 单独推送；`done.finishReason` 映射为 `stop（正常完成）` / `length（被截断）`；`length` 时出现黄色告警；没收到 `done` 就断流会提示「连接意外结束」 | 看文字逐字出现且结束原因为 `stop`；日志 `chat done` 里有 `completionTokens` 与 `finishReason` |
| JSON 解析失败有兜底 | `ExtractService` 先剥 Markdown 代码围栏，解析异常或字段越界不抛错，降级为 `warnings` 供人工核对 | 「公文抽取」粘贴非公文文本，仍返回字段 + warnings；单测见下方命令 |
| 异常有日志 | 每个请求生成 `traceId`，贯穿 start / first token / done / error 四条日志；未捕获异常统一返回 500 + traceId，并打 `error` 日志 | 把 Key 改错后发消息：页面显示错误与 traceId，`grep <traceId>` 后端日志能对上 |
| README 可复现 | 快速启动 + 现场演示 + 10 分钟自测 | 干净目录 clone 后只按本 README 操作 |

兜底逻辑的离线验证（不消耗模型额度）：

```bash
cd backed
./mvnw -Dtest=ExtractServiceTest test
```

覆盖三种情况：返回非 JSON、返回带 ```` ```json ```` 围栏、文种/日期越界。全部通过说明"模型乱答"不会让接口 500。

## 10 分钟自测（对应第 1 次汇报检查项）

在干净目录里重新 clone，只照本 README 操作并计时：

- [ ] 仓库无密钥：`git ls-files | grep -i env` 只应看到 `.env.example`
- [ ] 后端从 `backed/` 启动，无手工改代码
- [ ] 前端 `npm install && npm run dev` 一次成功
- [ ] 首字：等待时显示「等待中 x.x s」，收到首字后锁定耗时并变绿；超过 3 秒变红，先看「后端首字」排除网络因素
- [ ] 文字逐字出现，结束原因显示 `stop（正常完成）`
- [ ] 切换风格或改 `messagePrompt.md` 后重启，输出变化可解释
- [ ] 制造一次异常（例如把 `DEEPSEEK_API_KEY` 改错），页面显示错误信息和 `traceId`，后端日志能按该 `traceId` 查到

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 启动报 `Could not resolve placeholder 'DEEPSEEK_API_KEY'` 或 `apiKey cannot be null` | `.env` 不存在，或不是在 `backed/` 目录启动 |
| 模型返回 401 | Key 错误或已失效 |
| 模型返回 402 | 账户余额不足 |
| 模型返回 400/404，提示 model 相关 | `.env` 里的 `DEEPSEEK_MODEL` 写错 |
| 页面提示 `HTTP 400` 且内容是 style 相关 | `style` 只允许 严谨 / 温和 / 简洁 |
| 8080 或 5173 被占用 | 改 `application.yaml` 的 `server.port`，或 `vite.config.js` 的 `server.port`（同时改代理 target） |
| 首字明显 >3 秒 | 先看「后端首字」：后端也慢是模型/网络问题；后端快则查本机代理 |
| 回答一次性整段出现 | 中间层开启了响应缓冲；本项目已在后端设置 `X-Accel-Buffering: no`，若加了 Nginx 需关闭 `proxy_buffering` |
| 前端装依赖慢 | 换 npm 镜像：`npm config set registry https://registry.npmmirror.com` |

## 工程约定

- 密钥只放 `backed/.env`，该文件已在 `.gitignore` 中；提交前跑一次 `git status`。
- `target/`、`node_modules/`、`dist/`、`.idea/` 均不入库。
- 报错日志带 `traceId`，前端错误提示与后端日志能对上。
