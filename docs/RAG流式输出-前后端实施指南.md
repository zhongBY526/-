# RAG generate 节点流式输出 — 前后端实施指南

> 目标：把 `/api/rag/chat` 的一次性返回改造为 SSE 流式输出（边生成边显示），
> 保留状态图的 retrieve → evaluate → rewrite 重试循环不动。
> 本文档所有 API 已针对 langgraph4j 1.9.7 / langchain4j 1.23.0 用 javap 核实。

---

## 0. 现状诊断（先看你已经改了什么）

`RagWorkflow` 当前状态：
- 构造器已注入 `@Qualifier("openAiStreamingChatModel") StreamingChatModel chatModel` ✅ 方向正确
- generate 里写了 `SseEmitter answer = chatModel.chat(prompt);` ❌ 编译不过

**为什么编译不过**：
- `StreamingChatModel.chat(String, StreamingChatResponseHandler)` 返回 `void`，
  token 是通过**回调**推给你的，不是返回值
- `SseEmitter` 是 Spring MVC 的响应通道对象，只能由 **Controller 创建并返回**，
  模型层根本不知道它的存在

所以核心问题是：**Controller 创建的 SseEmitter，怎么"递"到状态图深处的 generate 节点手里？**

---

## 1. 方案选型

| 方案 | 思路 | 优点 | 缺点 |
|------|------|------|------|
| **A（推荐）** | emitter 通过 `RunnableConfig.addMetadata` 传入图，generate 改用带 config 的节点接口 | 图结构零改动；同步接口 `/chat` 继续可用 | 需要理解 metadata 传递和线程模型 |
| B | 把 generate 移出图，图只负责检索，Controller 拿到 context 后再流式生成 | 概念最简单 | 图不完整，"fallback 也走 generate"的语义丢了；面试讲状态图时打折扣 |

选 A。langgraph4j 原生支持两个关键 API（已核实存在）：

```java
// 1. 节点可以同时拿到 state 和 config
org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async(NodeActionWithConfig<S>)
// NodeActionWithConfig 签名: Map<String,Object> apply(S state, RunnableConfig config)

// 2. 运行时配置可以携带任意对象
RunnableConfig.builder().addMetadata("sse", emitter).build()
config.metadata("sse")  // 返回 Optional<Object>
```

**为什么不用 State 传 emitter**：State 的 Channel 存的是"数据"（会被克隆/序列化），
emitter 是"资源句柄"，塞进 State 属于滥用；metadata 就是官方留给这类运行时上下文的口子。

---

## 2. 后端实施步骤

### Step 1：AsyncConfig 加一个 RAG 专用线程池

文件：`config/AsyncConfig.java`

图的一次执行包含多轮 LLM 调用（evaluate/rewrite/generate），耗时可达几十秒，
绝不能占用 Tomcat 请求线程（SseEmitter 的意义就是释放请求线程）。

```java
// RAG 对话专用线程池：一次执行含多轮 LLM 调用，与请求线程、入库线程隔离
@Bean("ragChatExecutor")
public Executor ragChatExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("rag-chat-");
    executor.initialize();
    return executor;
}
```

⚠️ 不要复用 `ingestionExecutor`：入库高峰会把对话请求排队饿死，反之亦然。

### Step 2：RagWorkflow 改造（核心）

文件：`graph/RagWorkflow.java`

**2a. 构造器注入 executor，generate 节点换注册方式**

```java
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import java.util.concurrent.Executor;

// 构造器追加参数：
public RagWorkflow(..., @Qualifier("ragChatExecutor") Executor ragChatExecutor, ...) {
    this.ragChatExecutor = ragChatExecutor;
    ...
    // 原来: workflow.addNode("generate", node_async(this::generate));
    // 换成带 config 的版本（注意用类名限定，避免与 AsyncNodeAction.node_async 的静态导入混淆）
    workflow.addNode("generate", AsyncNodeActionWithConfig.node_async(this::generate));
    ...
}
```

**2b. generate 重写：回调 → emitter，闭锁等流结束**

```java
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.http.MediaType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

private Map<String, Object> generate(RagState state, RunnableConfig config) {
    String prompt = """
            ...（你现有的 prompt 原样保留）...
            """.formatted(state.question(),
        state.context().isBlank() ? "（未检索到资料）" : state.context());

    // 同步调用（老接口）拿不到 emitter，为 null 时退化为纯阻塞累积
    SseEmitter emitter = (SseEmitter) config.metadata("sse").orElse(null);

    // 来源必须先于 token 发：前端要先渲染引用编号，token 里才有 [n] 可对照
    if (emitter != null) {
        try {
            emitter.send(SseEmitter.event().name("sources")
                    .data(Map.of("sources", state.chunks(), "retryCount", state.retryCount()),
                          MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.info("SSE sources send stopped: {}", e.toString());
            emitter = null; // 客户端已断开，后续不再推送
        }
    }

    StringBuilder full = new StringBuilder();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    final SseEmitter sink = emitter; // lambda 里只能用 effectively final 变量

    chatModel.chat(prompt, new StreamingChatResponseHandler() {
        @Override
        public void onPartialResponse(String delta) {
            full.append(delta);
            if (sink == null) return;
            try {
                sink.send(SseEmitter.event().name("token")
                        .data(Map.of("delta", delta), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.info("SSE token send stopped: {}", e.toString());
            }
        }
        @Override
        public void onCompleteResponse(ChatResponse response) { latch.countDown(); }
        @Override
        public void onError(Throwable error) { failure.set(error); latch.countDown(); }
    });

    // 关键：节点必须阻塞到流结束才能返回，否则图立刻走到 END，
    // Controller 会提前 complete()，后面的 token 全丢
    try {
        if (!latch.await(90, TimeUnit.SECONDS)) {
            throw new IllegalStateException("generate 流式输出超时");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("generate 被中断", e);
    }
    if (failure.get() != null) {
        throw new IllegalStateException("模型流式调用失败", failure.get());
    }
    return Map.of(RagState.ANSWER, full.toString(), RagState.SOURCES, state.chunks());
}
```

**2c. 新增 chatStream 方法（老的 chat() 一行不改，继续可用）**

```java
public void chatStream(String question, List<Long> kbIds, SseEmitter emitter) {
    ragChatExecutor.execute(() -> {
        try {
            graph.invoke(
                    Map.of(RagState.QUESTION, question, RagState.KB_IDS, kbIds),
                    RunnableConfig.builder().addMetadata("sse", emitter).build())
                .orElseThrow();
            emitter.send(SseEmitter.event().name("done").data(Map.of(), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception e) {
            log.error("RAG stream failed", e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "回答生成失败，请稍后重试"), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ignored) { }
            emitter.complete(); // 无论成败都必须 complete，否则前端一直挂着等
        }
    });
}
```

### Step 3：RagChatController 加流式端点

文件：`controller/RagChatController.java`

```java
@PostMapping(value = "/chat/stream", produces = "text/event-stream")
public SseEmitter chatStream(@Valid @RequestBody RagChatRequest request, HttpServletResponse response) {
    User user = UserContext.currentUser();
    List<Long> kbIds = (request.kbIds() == null || request.kbIds().isEmpty())
            ? accessService.accessibleKbIds(user)
            : request.kbIds();
    kbIds.forEach(id -> accessService.requireAccess(user, id)); // 权限校验必须留在请求线程！
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Cache-Control", "no-cache");
    SseEmitter emitter = new SseEmitter(120_000L);
    ragWorkflow.chatStream(request.question(), kbIds, emitter);
    return emitter;
}
```

⚠️ **ThreadLocal 大坑**：`UserContext` 是 ThreadLocal。图在 `rag-chat-` 线程上跑，
那里 `UserContext.currentUser()` 是空的。所以**所有用到当前用户的逻辑（权限校验、kbIds 缺省计算）
必须在 Controller 请求线程里做完**，再把纯数据传进图。

### SSE 事件协议（前后端契约）

| 事件 | data | 时机 |
|------|------|------|
| `sources` | `{sources:[{index,text,file_name,score}], retryCount}` | generate 开始时（token 之前） |
| `token` | `{delta:"增量文本"}` | 每个模型增量 |
| `done` | `{}` | 图执行完毕 |
| `error` | `{message}` | 任何环节失败 |

与 `/api/chat/stream` 的 meta/token/done/error 风格保持一致，前端解析器可以复用。

---

## 3. 前端实施步骤

文件：`front/src/views/KbChatView.vue`，只改 `send()`，template/render 不动。

**为什么不能用 `api()` 封装**：它内部 `await res.json()` 会把整个响应当 JSON 一次性读完，
流式必须用 `fetch + response.body.getReader()` 手工解析。SSE 帧格式：
`event: xxx\ndata: {...}\n\n`，空行分帧。

```js
async function send(question) {
  messages.value.push({ role: 'user', content: question })
  // 先占位一条空 assistant 消息，token 到达后增量追加
  const i = messages.value.push({ role: 'assistant', content: '', sources: [], retryCount: 0 }) - 1
  const reply = messages.value[i] // 经响应式数组取出的对象是 reactive 代理，可直接改
  sending.value = true
  scrollBottom()
  try {
    const res = await fetch('/api/rag/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('token')}`, // api() 之外要手动带 token
      },
      body: JSON.stringify({ question, kbIds: selectedKbIds.value }),
    })
    if (!res.ok) {
      // 400/401 走的是 JSON 而非 SSE，别丢掉后端 message
      let msg = `HTTP ${res.status}`
      try { const b = await res.json(); if (b?.message) msg = b.message } catch { }
      throw new Error(msg)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }) // 半个中文字符留到下一帧
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        let event = 'message'
        const dataLines = []
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
        }
        if (!dataLines.length) continue
        const data = JSON.parse(dataLines.join('\n'))
        if (event === 'sources') {
          reply.sources = data.sources
          reply.retryCount = data.retryCount
        } else if (event === 'token') {
          reply.content += data.delta ?? ''
          scrollBottom()
        } else if (event === 'error') {
          throw new Error(data.message ?? '回答生成失败')
        }
      }
    }
  } catch (e) {
    reply.content = reply.content || `出错了：${e.message}`
  } finally {
    sending.value = false
    scrollBottom()
  }
}
```

要点：
- `reply.content += delta` 每次触发响应式更新，`v-html="render(reply.content)"` 自动重渲染 Markdown，
  不需要额外"打字机"逻辑
- Markdown 渲染中途会出现未闭合语法（如半个代码块），markdown-it 容错处理，无需特殊处理
- 想加"停止"按钮：参考 ChatViews 的 `AbortController`，`fetch` 传 `signal`，按钮调 `controller.abort()`

---

## 4. 验证清单（按顺序）

1. **编译**：`cd backed && .\mvnw.cmd compile`
2. **重启后端**（IDEA），确认无 Bean 装配报错
3. **curl 验证 SSE**（PowerShell 下用 curl.exe，`-N` 禁缓冲）：
   ```powershell
   # 先登录拿 token，再：
   curl.exe -N -X POST http://localhost:8080/api/rag/chat/stream `
     -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
     --data-binary "@question.json"
   ```
   预期：先看到 `event: sources`，随后一串 `event: token` **逐条到达**（不是一次全出），最后 `event: done`
4. **老接口回归**：`POST /api/rag/chat` 仍一次性返回完整 RagResult（generate 对 emitter=null 的退化路径）
5. **前端**：`/kb/chat` 提问，观察回答逐字出现、引用面板在回答前就可展开、retryCount 提示正常
6. **断连测试**：回答中途刷新页面，后端日志应出现 send stopped 而非异常刷屏
7. **越权测试**：body 里传别人的 kbId，应 403（校验在请求线程，未受异步影响）

---

## 5. 坑位说明（本次新增的 4 个）

| 坑 | 现象 | 规避 |
|----|------|------|
| 节点不等流结束就返回 | 前端只收到前几个 token 就 done | generate 里 CountDownLatch 阻塞到 onComplete/onError |
| ThreadLocal 跨线程丢失 | 图里取 UserContext 为空 → NPE/越权 | 用户相关逻辑全部留在 Controller 线程 |
| 异常路径不 complete | 前端永远"思考中" | chatStream 的 catch 里也必须 send error + complete |
| 用 api() 调流式接口 | 一直转圈直到超时才一次性出全文 | SSE 必须 getReader() 手工解析 |

既有坑复用提醒：SseEmitter 超时（120s）要大于模型最长生成时间；
`spring.mvc.async.request-timeout: 60s` 会被 `new SseEmitter(120_000L)` 的显式超时覆盖，无需改 yaml。

---

## 6. 面试怎么讲这个改造

1. **为什么流式**：首字延迟（TTFT）从"全文生成完"降到"第一个 token"，长回答体验质变；SSE 是单向推送，
   比 WebSocket 轻，天然适合 LLM 输出场景
2. **线程模型**：Tomcat 请求线程创建 SseEmitter 后立即归还线程池（Servlet 3 异步），
   图执行在业务线程池，token 回调在 HTTP 客户端线程——三方接力，靠 latch 保证图的节点语义不被异步打破
3. **上下文传递**：RunnableConfig.metadata 是 langgraph4j 的运行时上下文通道，
   与 State（业务数据，可克隆/持久化）职责分离——类比 Spring 的 RequestContextHolder 与请求参数的关系
4. **兼容性设计**：同一个 generate 节点，有 emitter 走推送、无 emitter 走纯累积，
   同步/流式两个端点共享全部图逻辑，没有 if-else 复制一份流程