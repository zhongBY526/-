# 企业内部知识库 RAG —— 实现清单与学习步骤

> 本文档基于已确认的改造计划编写。**阶段一、二已由助手实现并编译通过**，阶段三至六留给你亲手实现。
> 每个阶段包含：目标 → 实现步骤（含参考代码）→ 验证清单 → 学习要点与坑。
> 建议顺序：先跑通阶段一、二的验证，确认环境正常，再从阶段三开始逐段实现。

---

## 0. 当前进度与文件清单

### 0.1 阶段进度总览

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | docker-compose / init.sql / pom 依赖 / yaml / 配置类 | 已完成（编译通过） |
| 二 | 认证 auth 包 + 知识库权限 kb 包 | 已完成（编译通过） |
| 三 | 文档上传解析入库 document 包 | **待你实现**（仅 RagDocument.java 已建好） |
| 四 | 混合检索 + 重排 + RagWorkflow + 问答 API | **待你实现**（仅 MetadataKeys.java 已建好） |
| 五 | 前端路由 / 登录 / 知识库管理 / 问答页 | **待你实现** |
| 六 | 单测 + 全链路验证 | **待你执行** |

### 0.2 本次会话已写入的文件（可保留继续，也可自行回退）

修改的已有文件：
- `backed/pom.xml`（新增 7 个依赖）
- `backed/src/main/resources/application.yaml`（datasource / rag.* / 模型名修正）
- `backed/.env.example`（新增可选变量说明）
- `backed/.../common/GlobalExceptionHandler.java`（新增 401/403 处理）
- `backed/.../config/EmbeddingConfig.java`（Ollama 配置外部化）
- `backed/.../config/EmbeddingStoreConfig.java`（InMemory → pgvector）

新增的文件：
- `docker-compose.yml`、`backed/src/main/resources/db/init.sql`
- `config/`：`AsyncConfig`、`ScoringModelConfig`、`WebConfig`
- `common/`：`UnauthorizedException`、`ForbiddenException`
- `auth/`：`User`、`UserView`、`UserRepository`、`TokenStore`、`UserContext`、`AuthInterceptor`、`AuthController`、`AdminSeeder`
- `kb/`：`KnowledgeBase`、`KbRepository`、`KbAccessService`、`KnowledgeBaseService`、`KnowledgeBaseController`
- `dto/`：`LoginRequest`、`LoginResponse`、`KbRequest`
- `document/RagDocument.java`、`retrieval/MetadataKeys.java`

> 注意：`git status` 里还有一批**更早会话**遗留的未提交改动（如 `DeepseekStreamChat.java`、`TraceIdFilter.java`、`graph/` 包等），不属于本次改造，回退时不要误伤。
> 若想全部从零自己写：先 `git stash` 或手工备份，再删除上述新增文件、还原修改文件。**推荐保留阶段一、二**（已验证可编译），把精力集中在检索与 RAG 核心链路的学习上。

### 0.3 环境前置条件

- [ ] Docker Desktop 已安装并启动
- [ ] Ollama 已安装，`ollama pull bge-m3` 已完成，`ollama serve` 在 11434 端口运行
- [ ] `backed/.env` 中已有有效的 `DEEPSEEK_API_KEY`
- [ ] （可选）注册 SiliconFlow 获取免费 key，写入 `.env` 的 `SILICONFLOW_API_KEY`（不配则自动跳过重排）

---

## 1. 总体架构与学习路线

### 1.1 架构图

```
前端(Vue3 + vue-router)
   │  Authorization: Bearer <token>
   ▼
AuthInterceptor ──> Controllers (/api/kb, /api/rag, /api/auth)
   │
   ├─ 入库链路:
   │   Upload → 解析(pdfbox/poi/text) → recursive 切分(500/50)
   │          → bge-m3 向量化(1024维) → pgvector
   │          (每段带 kb_id/doc_id/file_name/chunk_index 元数据)
   │
   └─ 问答链路 (langgraph4j 状态图):
       retrieve(向量+关键词双路 → RRF融合 → rerank)
          → evaluate(LLM判断资料是否足够)
          → 足够: generate(带[n]引用的回答)
          → 不足且重试<2: rewrite(LLM改写查询+提取关键词) → retrieve
          → 重试耗尽: generate(明示资料不足)

存储: PostgreSQL(pgvector) 一个库承载
      业务表 users / knowledge_base / rag_document + 向量表 rag_embedding
模型: 对话=DeepSeek deepseek-chat | Embedding=Ollama bge-m3
      Rerank=SiliconFlow bge-reranker-v2-m3(可选)
```

### 1.2 技术栈与版本（重要）

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 3.5.16 | parent POM 管理 Spring 系依赖版本 |
| langchain4j 核心 | 1.23.0 | 稳定版号线 |
| langchain4j 集成模块 | 1.23.0-beta33 | pgvector/jina/parser/starter 走 beta 线，**两条版本线不能混用** |
| langgraph4j-core | 1.9.7 | `Channels.base()` 无参重载已移除；`invoke(Map)` 已弃用 |
| PostgreSQL + pgvector | pg17 镜像 | 向量表自动创建，业务表走 init.sql |
| Vue | 3.5 + pinia | 需新增 vue-router@4 |

### 1.3 学习目标（面试可讲的点）

1. RAG 完整链路：解析 → 切分 → 向量化 → 混合检索 → 重排 → 生成 → 引用溯源
2. 为什么用 RRF 融合而不是简单分数相加（向量分数与关键词分数量纲不同）
3. langgraph4j 自适应检索：评估-改写-重试环，以及业务重试计数与框架 recursionLimit 两道防线
4. 元数据过滤实现多租户/权限隔离（kb_id 过滤下推到向量库）
5. 异步入库 + 状态机（PROCESSING/READY/FAILED）+ 前端轮询
6. 降级设计：无 rerank key 时链路不中断

---

## 2. 阶段一：基础设施（已完成）—— 验证与理解

### 2.1 验证清单

- [ ] 仓库根目录执行 `docker compose up -d`
- [ ] `docker exec -it rag-pgvector psql -U rag -d ragdb -c "\dt"` 能看到 `users`、`knowledge_base`、`rag_document` 三张表
- [ ] `backed` 目录执行 `.\mvnw.cmd -q compile` 无报错
- [ ] 启动后端（IDEA 运行 BackedApplication，工作目录设为 `backed/`），日志出现种子账号创建信息
- [ ] 启动后 psql 再查 `\dt`，多出 `rag_embedding` 表（PgVectorEmbeddingStore 自动建表）

### 2.2 关键文件导读

| 文件 | 看什么 |
|---|---|
| `docker-compose.yml` | init.sql 挂载到 `/docker-entrypoint-initdb.d/`，**只在数据卷首次初始化时执行**；改表结构需删卷重建或手动执行 SQL |
| `db/init.sql` | 三张业务表 + 外键级联（删知识库自动删文档记录）；`CREATE EXTENSION vector` |
| `pom.xml` | 7 个新依赖的 groupId/version；`spring-security-crypto` 只用 BCrypt 不引完整 Security |
| `application.yaml` | `rag.*` 配置节；两处模型名默认值已从无效的 `deepseek-flash` 修正为 `deepseek-chat` |
| `EmbeddingStoreConfig` | `PgVectorEmbeddingStore.builder()` 的 host/port/database/table/dimension/createTable |
| `ScoringModelConfig` | `@ConditionalOnProperty` + 空 key 返回 null 的双保险；消费方用 `ObjectProvider<ScoringModel>` 判空降级 |
| `AsyncConfig` | 入库专用线程池 `ingestionExecutor`，与 Web 线程隔离 |

### 2.3 学习要点

- **dimension=1024 必须与 bge-m3 输出维度一致**，换 embedding 模型要同步改维度并重建向量表（旧向量不可混用）
- pgvector 的 `rag_embedding` 表结构：`embedding_id UUID / embedding vector / text / metadata JSONB`——阶段四关键词检索直接查这张表
- 思考题：为什么业务表用 JdbcTemplate 而向量用 langchain4j Store？（答案：向量读写有专用协议与索引，业务 CRUD 用 SQL 更直观可控）

---

## 3. 阶段二：认证与权限（已完成）—— 验证与理解

### 3.1 请求流程

```
POST /api/auth/login (开放)
  → UserRepository.findByUsername → BCrypt.matches
  → TokenStore.issue 签发 UUID token（内存 Map，重启失效）
  → 返回 { token, user{id,username,role,department} }

后续请求带 Authorization: Bearer <token>
  → AuthInterceptor.preHandle 解析 token → UserContext.set(ThreadLocal)
  → Controller 里 UserContext.currentUser() 取当前用户
  → afterCompletion 清理 ThreadLocal（防线程池复用串号）
```

拦截范围：`/api/kb/**`、`/api/rag/**`、`/api/auth/me|logout`。
旧的 `/api/chat`、`/api/extract` 等保持开放，不破坏现有功能。

### 3.2 权限矩阵（KbAccessService）

| 操作 | ADMIN | 所有者 | 同部门(DEPARTMENT库) | 其他人(PUBLIC库) | 其他人(PRIVATE库) |
|---|---|---|---|---|---|
| 查看/检索/上传文档 | 是 | 是 | 是 | 是 | 否 |
| 修改/删除知识库 | 是 | 是 | 否 | 否 | 否 |
| 删除文档 | 是 | 是 | 仅本人上传的 | 仅本人上传的 | 否 |

种子账号：`admin/admin123`（ADMIN，管理部）、`demo/demo123`（USER，技术部）。

### 3.3 验证清单（PowerShell）

```powershell
# 1. 登录拿 token
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}'
$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }

# 2. 建知识库
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/kb -Headers $headers `
  -ContentType "application/json" -Body '{"name":"技术部文档","description":"测试","visibility":"DEPARTMENT"}'

# 3. 列表（应只返回可访问的）
Invoke-RestMethod -Uri http://localhost:8080/api/kb -Headers $headers

# 4. 不带 token 访问 → 应返回 401
Invoke-RestMethod -Uri http://localhost:8080/api/kb   # 预期报错 401
```

- [ ] 登录成功返回 token 与 user（**响应里绝不能出现 passwordHash**）
- [ ] 带 token 建库/列表成功
- [ ] 无 token 返回 401，错误密码返回 401
- [ ] 用 demo 登录，看不到 admin 的 PRIVATE 库；看得到 DEPARTMENT(管理部) 以外的公开库

### 3.4 学习要点

- `User`（含哈希，内部用）与 `UserView`（对外输出）分离，防止敏感字段泄露
- ThreadLocal 必须在 afterCompletion 清理——Tomcat 线程池复用线程，不清理会把上一个请求的用户带给下一个请求
- token 存内存是学习简化；面试可延伸：JWT 无状态 vs Redis 集中式会话的取舍
- `@ConditionalOnProperty` 对"属性存在但为空串"仍会匹配，所以 ScoringModelConfig 里还要判空返回 null

---

## 4. 阶段三：文档入库管道（待你实现）

> 目标：上传 PDF/Word/MD/TXT → 异步解析切分向量化 → 状态可查 → 可删除（连同向量）。
> 包路径：`com.intern.backed.document`。已有 `RagDocument.java`（record + 三个状态常量），无需重建。
> 实现顺序：Repository → IngestionService → DocumentService → Controller → 异常处理补充。

### 4.1 步骤一：DocumentRepository（JdbcTemplate CRUD）

新建 `document/DocumentRepository.java`：

```java
package com.intern.backed.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class DocumentRepository {
    private static final RowMapper<RagDocument> MAPPER = (rs, i) -> new RagDocument(
            rs.getLong("id"), rs.getLong("kb_id"), rs.getString("file_name"),
            rs.getString("file_type"), rs.getLong("file_size"), rs.getString("status"),
            rs.getString("error_msg"), rs.getInt("chunk_count"), rs.getLong("uploaded_by"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long kbId, String fileName, String fileType, long fileSize, long uploadedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rag_document(kb_id, file_name, file_type, file_size, status, uploaded_by) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, kbId);
            ps.setString(2, fileName);
            ps.setString(3, fileType);
            ps.setLong(4, fileSize);
            ps.setString(5, RagDocument.PROCESSING);
            ps.setLong(6, uploadedBy);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public List<RagDocument> findByKbId(long kbId) {
        return jdbc.query("SELECT * FROM rag_document WHERE kb_id = ? ORDER BY id DESC", MAPPER, kbId);
    }

    public Optional<RagDocument> findById(long id) {
        return jdbc.query("SELECT * FROM rag_document WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void markReady(long id, int chunkCount) {
        jdbc.update("UPDATE rag_document SET status = ?, chunk_count = ?, error_msg = NULL WHERE id = ?",
                RagDocument.READY, chunkCount, id);
    }

    public void markFailed(long id, String errorMsg) {
        jdbc.update("UPDATE rag_document SET status = ?, error_msg = ? WHERE id = ?",
                RagDocument.FAILED, errorMsg, id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM rag_document WHERE id = ?", id);
    }
}
```

要点：`KeyHolder` 取自增主键（可对照已写好的 `KbRepository.insert` 学习同一模式）。

### 4.2 步骤二：IngestionService（异步解析入库，核心）

新建 `document/IngestionService.java`：

```java
package com.intern.backed.document;

import com.intern.backed.retrieval.MetadataKeys;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;

@Slf4j
@Service
public class IngestionService {
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentRepository documentRepository;
    private final int chunkSize;
    private final int chunkOverlap;

    public IngestionService(EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            DocumentRepository documentRepository,
                            @Value("${rag.ingestion.chunk-size}") int chunkSize,
                            @Value("${rag.ingestion.chunk-overlap}") int chunkOverlap) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentRepository = documentRepository;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    // 跨 Bean 调用才会走代理，@Async 生效；同类内部自调用会失效（经典坑）
    @Async("ingestionExecutor")
    public void ingest(long docId, long kbId, String fileName, byte[] content) {
        try {
            Document document = parse(fileName, content);
            // 元数据值统一存字符串，检索时才能用 metadataKey(...).isEqualTo(String) 过滤
            document.metadata()
                    .put(MetadataKeys.KB_ID, String.valueOf(kbId))
                    .put(MetadataKeys.DOC_ID, String.valueOf(docId))
                    .put(MetadataKeys.FILE_NAME, fileName);

            DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
            List<TextSegment> segments = splitter.split(document);
            if (segments.isEmpty()) {
                documentRepository.markFailed(docId, "文档内容为空或无法解析出文本");
                return;
            }
            for (int i = 0; i < segments.size(); i++) {
                segments.get(i).metadata().put(MetadataKeys.CHUNK_INDEX, String.valueOf(i));
            }

            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            documentRepository.markReady(docId, segments.size());
            log.info("文档入库完成 docId={}, chunks={}", docId, segments.size());
        } catch (Exception e) {
            log.error("文档入库失败 docId={}", docId, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // error_msg 列宽 1024，截断防二次报错
            documentRepository.markFailed(docId, msg.length() > 1000 ? msg.substring(0, 1000) : msg);
        }
    }

    private Document parse(String fileName, byte[] content) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        DocumentParser parser = switch (ext) {
            case "pdf" -> new ApachePdfBoxDocumentParser();
            case "doc", "docx" -> new ApachePoiDocumentParser();
            case "md", "txt" -> new TextDocumentParser();
            default -> throw new IllegalArgumentException("不支持的文件类型: " + ext);
        };
        return parser.parse(new ByteArrayInputStream(content));
    }
}
```

要点：
- `DocumentSplitters.recursive(500, 50)`：按段落→句子→词递归切，块长 500 token、重叠 50，重叠是为了避免语义被切断
- 先写元数据再切分：切分器会把 Document 的 metadata 复制到每个段
- `embedAll` 批量向量化比逐段 `embed` 快得多（Ollama 一次请求多段）
- 失败必须落 FAILED 状态，否则前端永远轮询 PROCESSING

### 4.3 步骤三：DocumentService（上传/删除编排）

新建 `document/DocumentService.java`：

```java
package com.intern.backed.document;

import com.intern.backed.auth.User;
import com.intern.backed.common.ForbiddenException;
import com.intern.backed.kb.KbAccessService;
import com.intern.backed.kb.KnowledgeBase;
import com.intern.backed.retrieval.MetadataKeys;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentService {
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "doc", "docx", "md", "txt");

    private final DocumentRepository documentRepository;
    private final KbAccessService accessService;
    private final IngestionService ingestionService;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public DocumentService(DocumentRepository documentRepository, KbAccessService accessService,
                           IngestionService ingestionService, EmbeddingStore<TextSegment> embeddingStore) {
        this.documentRepository = documentRepository;
        this.accessService = accessService;
        this.ingestionService = ingestionService;
        this.embeddingStore = embeddingStore;
    }

    public RagDocument upload(User user, long kbId, MultipartFile file) {
        accessService.requireAccess(user, kbId);
        String fileName = file.getOriginalFilename();
        if (file.isEmpty() || fileName == null || !fileName.contains(".")) {
            throw new IllegalArgumentException("文件为空或文件名不合法");
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 pdf/doc/docx/md/txt");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long docId = documentRepository.insert(kbId, fileName, ext, file.getSize(), user.id());
        ingestionService.ingest(docId, kbId, fileName, bytes);
        return documentRepository.findById(docId).orElseThrow();
    }

    public List<RagDocument> list(User user, long kbId) {
        accessService.requireAccess(user, kbId);
        return documentRepository.findByKbId(kbId);
    }

    public void delete(User user, long docId) {
        RagDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new ForbiddenException("文档不存在"));
        KnowledgeBase kb = accessService.requireAccess(user, doc.kbId());
        // 上传者本人、知识库所有者、管理员可删
        if (!user.isAdmin() && kb.ownerId() != user.id() && doc.uploadedBy() != user.id()) {
            throw new ForbiddenException("仅上传者、知识库所有者或管理员可删除文档");
        }
        // 先删向量再删记录，避免孤儿向量被检索到
        embeddingStore.removeAll(metadataKey(MetadataKeys.DOC_ID).isEqualTo(String.valueOf(docId)));
        documentRepository.deleteById(docId);
    }
}
```

### 4.4 步骤四：DocumentController

新建 `document/DocumentController.java`：

```java
package com.intern.backed.document;

import com.intern.backed.auth.UserContext;
import com.intern.backed.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/{kbId}/documents")
    public ApiResponse<RagDocument> upload(@PathVariable long kbId,
                                           @RequestParam("file") MultipartFile file) {
        return new ApiResponse<>(200, "ok",
                documentService.upload(UserContext.currentUser(), kbId, file), null);
    }

    @GetMapping("/{kbId}/documents")
    public ApiResponse<List<RagDocument>> list(@PathVariable long kbId) {
        return new ApiResponse<>(200, "ok",
                documentService.list(UserContext.currentUser(), kbId), null);
    }

    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Void> delete(@PathVariable long docId) {
        documentService.delete(UserContext.currentUser(), docId);
        return new ApiResponse<>(200, "ok", null, null);
    }
}
```

路径都在 `/api/kb/**` 下，自动被 AuthInterceptor 覆盖，无需改 WebConfig。

### 4.5 步骤五：GlobalExceptionHandler 补 400 处理

上面用 `IllegalArgumentException` 表达参数错误，但现有处理器会把它归入 500。
在 `GlobalExceptionHandler` 中新增（放在 handleUnexpected 之前）：

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e,
                                                               HttpServletRequest request) {
    String traceId = resolveTraceId(request);
    log.warn("bad argument, traceId={}, message={}", traceId, e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiResponse<>(400, e.getMessage(), null, traceId));
}
```

### 4.6 验证清单

准备一个测试文件 `test.md`（内容写几段你熟悉的技术知识，如 HashMap 原理，方便阶段四验证问答）。

```powershell
# 沿用阶段二的 $headers；假设知识库 id=1
# 上传（PowerShell 5.1 用 -Form 需 PS7+，低版本可用 curl.exe）
curl.exe -s -X POST http://localhost:8080/api/kb/1/documents -H "Authorization: Bearer $token" -F "file=@test.md"

# 轮询状态：PROCESSING → READY，chunkCount > 0
curl.exe -s http://localhost:8080/api/kb/1/documents -H "Authorization: Bearer $token"

# 向量落库验证
docker exec -it rag-pgvector psql -U rag -d ragdb -c "SELECT count(*), metadata->>'file_name' FROM rag_embedding GROUP BY 2;"
```

- [ ] 上传 md 后状态最终 READY，chunk_count 与向量行数一致
- [ ] 上传 pdf/docx 各验证一次（解析器不同）
- [ ] 上传 .exe 等非法类型返回 400；空文件返回 400
- [ ] 上传损坏的 pdf → 状态 FAILED 且 error_msg 有内容（不会卡 PROCESSING）
- [ ] 删除文档后向量行数减少；demo 用户删 admin 上传的文档（非本人）返回 403
- [ ] 无 token 上传返回 401

### 4.7 本阶段坑位预警

1. **@Async 自调用失效**：必须由 Controller/DocumentService 调 IngestionService（跨 Bean 走代理）；若在同类里 this.ingest() 则同步执行
2. **异步线程无 UserContext**：ThreadLocal 不跨线程，所以 upload 里先把 user.id() 取出传参，不要在 ingest 里调 UserContext.currentUser()
3. **元数据值必须是字符串**：kb_id 存 Long 会导致阶段四 `isEqualTo("1")` 过滤不中
4. **MultipartFile 流只能读一次**：先 getBytes() 再交给异步线程，不要把 MultipartFile 直接传进 @Async 方法（请求结束后临时文件可能被清理）

---

## 5. 阶段四：混合检索 + 重排 + 自适应 RAG（待你实现，核心中的核心）

> 包路径：`com.intern.backed.retrieval`（已有 MetadataKeys）与 `com.intern.backed.graph`（新增 RagState/RagWorkflow，不动旧 Workflow.java）。
> 实现顺序：RetrievedChunk → HybridRetrievalService → RagState → RagWorkflow → DTO/Controller。

### 5.1 原理：为什么要混合检索 + RRF + 重排

- **向量路**：bge-m3 语义相似度，擅长"换个说法问同一件事"，但对精确术语/型号/人名可能漏召
- **关键词路**：SQL ILIKE 精确包含匹配，补向量的短板；中文不依赖 PG 分词扩展（zhparser 部署重，故不用 tsvector）
- **RRF（Reciprocal Rank Fusion）**：两路分数量纲不同不能直接相加，只用**排名**融合：
  `RRF(d) = Σ 1/(k + rank_i(d))`，k=60 是经验常数，rank 从 1 开始
- **重排**：融合后前 20 段交给 cross-encoder（bge-reranker-v2-m3）逐段精打分，取前 5；比向量相似度准得多
- **降级**：未配 SILICONFLOW_API_KEY 时容器无 ScoringModel Bean，直接取 RRF 前 5，链路不中断

### 5.2 步骤一：RetrievedChunk + HybridRetrievalService

新建 `retrieval/RetrievedChunk.java`：

```java
package com.intern.backed.retrieval;

import java.util.Map;

// 检索结果段：文本 + 元数据(file_name等) + 分数，供引用溯源
public record RetrievedChunk(String text, Map<String, String> metadata, double score) {}
```

新建 `retrieval/HybridRetrievalService.java`：

```java
package com.intern.backed.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Slf4j
@Service
public class HybridRetrievalService {
    private static final int RRF_K = 60;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<ScoringModel> scoringModelProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int vectorTopK;
    private final int keywordTopK;
    private final int finalTopK;
    private final double minScore;
    private final String table;

    public HybridRetrievalService(EmbeddingStore<TextSegment> embeddingStore,
                                  EmbeddingModel embeddingModel,
                                  JdbcTemplate jdbc,
                                  ObjectProvider<ScoringModel> scoringModelProvider,
                                  @Value("${rag.retrieval.vector-top-k}") int vectorTopK,
                                  @Value("${rag.retrieval.keyword-top-k}") int keywordTopK,
                                  @Value("${rag.retrieval.final-top-k}") int finalTopK,
                                  @Value("${rag.retrieval.min-score}") double minScore,
                                  @Value("${rag.pgvector.table}") String table) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.scoringModelProvider = scoringModelProvider;
        this.vectorTopK = vectorTopK;
        this.keywordTopK = keywordTopK;
        this.finalTopK = finalTopK;
        this.minScore = minScore;
        this.table = table;
    }

    public List<RetrievedChunk> retrieve(String query, List<String> keywords, List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return List.of();
        }
        List<String> kbIdStrings = kbIds.stream().map(String::valueOf).toList();
        Map<String, Candidate> candidates = new LinkedHashMap<>();

        // ① 向量路：kb_id 过滤下推到向量库，权限隔离在检索层生效
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(vectorTopK)
                .minScore(minScore)
                .filter(metadataKey(MetadataKeys.KB_ID).isIn(kbIdStrings))
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        for (int rank = 0; rank < matches.size(); rank++) {
            EmbeddingMatch<TextSegment> m = matches.get(rank);
            candidates.computeIfAbsent(m.embeddingId(),
                            id -> new Candidate(m.embedded().text(), m.embedded().metadata().toMap()))
                    .addRrf(rank + 1);
        }

        // ② 关键词路：直接查 rag_embedding 表的 text 列
        List<KeywordRow> rows = keywordSearch(keywords, kbIdStrings);
        for (int rank = 0; rank < rows.size(); rank++) {
            KeywordRow row = rows.get(rank);
            candidates.computeIfAbsent(row.embeddingId(),
                            id -> new Candidate(row.text(), parseMetadata(row.metadataJson())))
                    .addRrf(rank + 1);
        }

        // ③ RRF 融合排序，取前 vectorTopK 个候选
        List<Candidate> fused = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed())
                .limit(vectorTopK)
                .toList();
        if (fused.isEmpty()) {
            return List.of();
        }

        // ④ 重排（可选）：有 ScoringModel 则精打分取前 finalTopK，无则降级取 RRF 前 finalTopK
        ScoringModel reranker = scoringModelProvider.getIfAvailable();
        if (reranker != null) {
            try {
                List<TextSegment> segments = fused.stream()
                        .map(c -> TextSegment.from(c.text(), Metadata.from(c.metadata())))
                        .toList();
                List<Double> scores = reranker.scoreAll(query, segments).content();
                List<RetrievedChunk> reranked = new ArrayList<>();
                for (int i = 0; i < fused.size(); i++) {
                    reranked.add(new RetrievedChunk(fused.get(i).text(), fused.get(i).metadata(), scores.get(i)));
                }
                reranked.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed);
                return reranked.stream().limit(finalTopK).toList();
            } catch (Exception e) {
                log.warn("rerank 调用失败，降级用 RRF 排序: {}", e.getMessage());
            }
        }
        return fused.stream().limit(finalTopK)
                .map(c -> new RetrievedChunk(c.text(), c.metadata(), c.rrfScore()))
                .toList();
    }

    private List<KeywordRow> keywordSearch(List<String> keywords, List<String> kbIdStrings) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        String kbPlaceholders = String.join(",", Collections.nCopies(kbIdStrings.size(), "?"));
        String likeClauses = keywords.stream().map(k -> "text ILIKE ?").collect(Collectors.joining(" OR "));
        String sql = "SELECT embedding_id, text, metadata FROM " + table
                + " WHERE metadata->>'kb_id' IN (" + kbPlaceholders + ")"
                + " AND (" + likeClauses + ") LIMIT ?";
        List<Object> args = new ArrayList<>(kbIdStrings);
        keywords.forEach(k -> args.add("%" + k + "%"));
        args.add(keywordTopK);
        return jdbc.query(sql,
                (rs, i) -> new KeywordRow(rs.getString("embedding_id"), rs.getString("text"), rs.getString("metadata")),
                args.toArray());
    }

    private Map<String, String> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private record KeywordRow(String embeddingId, String text, String metadataJson) {}

    private static final class Candidate {
        private final String text;
        private final Map<String, String> metadata;
        private double rrfScore;

        Candidate(String text, Map<String, String> metadata) {
            this.text = text;
            this.metadata = metadata;
        }

        void addRrf(int rank) {
            rrfScore += 1.0 / (RRF_K + rank);
        }

        String text() { return text; }
        Map<String, String> metadata() { return metadata; }
        double rrfScore() { return rrfScore; }
    }
}
```

要点：
- 两路用 `embeddingId` 去重：同一段被两路同时召回时 RRF 分数叠加（这正是混合检索的收益来源）
- SQL 全部用占位符，表名来自配置非用户输入，无注入风险
- rerank 失败也要降级而不是抛错（外部 API 不可靠是常态）

### 5.3 步骤二：RagState（langgraph4j 状态）

新建 `graph/RagState.java`（与旧 State.java 并存，互不影响）：

```java
package com.intern.backed.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

public class RagState extends AgentState {
    public static final String QUESTION = "question";
    public static final String REWRITTEN_QUERY = "rewrittenQuery";
    public static final String KEYWORDS = "keywords";
    public static final String KB_IDS = "kbIds";
    public static final String CHUNKS = "chunks";
    public static final String CONTEXT = "context";
    public static final String SUFFICIENT = "sufficient";
    public static final String RETRY_COUNT = "retryCount";
    public static final String ANSWER = "answer";
    public static final String SOURCES = "sources";

    // 1.9.7 中 Channels.base() 无参重载已移除，必须传默认值 Supplier
    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(QUESTION, Channels.base(() -> "")),
            Map.entry(REWRITTEN_QUERY, Channels.base(() -> "")),
            Map.entry(KEYWORDS, Channels.<List<String>>base(() -> List.of())),
            Map.entry(KB_IDS, Channels.<List<Long>>base(() -> List.of())),
            Map.entry(CHUNKS, Channels.<List<Map<String, Object>>>base(() -> List.of())),
            Map.entry(CONTEXT, Channels.base(() -> "")),
            Map.entry(SUFFICIENT, Channels.base(() -> false)),
            Map.entry(RETRY_COUNT, Channels.base(() -> 0)),
            Map.entry(ANSWER, Channels.base(() -> "")),
            Map.entry(SOURCES, Channels.<List<Map<String, Object>>>base(() -> List.of()))
    );

    public RagState(Map<String, Object> initData) {
        super(initData);
    }

    public String question() { return this.<String>value(QUESTION).orElse(""); }
    public String rewrittenQuery() { return this.<String>value(REWRITTEN_QUERY).orElse(""); }
    public List<String> keywords() { return this.<List<String>>value(KEYWORDS).orElse(List.of()); }
    public List<Long> kbIds() { return this.<List<Long>>value(KB_IDS).orElse(List.of()); }
    public List<Map<String, Object>> chunks() { return this.<List<Map<String, Object>>>value(CHUNKS).orElse(List.of()); }
    public String context() { return this.<String>value(CONTEXT).orElse(""); }
    public boolean sufficient() { return this.<Boolean>value(SUFFICIENT).orElse(false); }
    public int retryCount() { return this.<Integer>value(RETRY_COUNT).orElse(0); }
    public String answer() { return this.<String>value(ANSWER).orElse(""); }
    public List<Map<String, Object>> sources() { return this.<List<Map<String, Object>>>value(SOURCES).orElse(List.of()); }
}
```

### 5.4 步骤三：RagWorkflow（@Component，构造器注入，不用静态 main）

新建 `graph/RagWorkflow.java`：

```java
package com.intern.backed.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intern.backed.dto.RagResult;
import com.intern.backed.retrieval.HybridRetrievalService;
import com.intern.backed.retrieval.MetadataKeys;
import com.intern.backed.retrieval.RetrievedChunk;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
@Component
public class RagWorkflow {
    private final HybridRetrievalService retrievalService;
    private final ChatModel chatModel;
    private final OpenAiChatModel jsonChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRetry;
    private final CompiledGraph<RagState> graph;

    // 容器里有两个 ChatModel 候选（openAiChatModel / openAiJsonChatModel），必须 @Qualifier 指名
    public RagWorkflow(HybridRetrievalService retrievalService,
                       @Qualifier("openAiChatModel") ChatModel chatModel,
                       @Qualifier("openAiJsonChatModel") OpenAiChatModel jsonChatModel,
                       @Value("${rag.retry.max}") int maxRetry) throws GraphStateException {
        this.retrievalService = retrievalService;
        this.chatModel = chatModel;
        this.jsonChatModel = jsonChatModel;
        this.maxRetry = maxRetry;

        var workflow = new StateGraph<>(RagState.SCHEMA, RagState::new);
        workflow.addNode("retrieve", node_async(this::retrieve));
        workflow.addNode("evaluate", node_async(this::evaluate));
        workflow.addNode("rewrite", node_async(this::rewrite));
        workflow.addNode("generate", node_async(this::generate));
        workflow.addEdge(START, "retrieve");
        workflow.addEdge("retrieve", "evaluate");
        workflow.addEdge("rewrite", "retrieve");
        workflow.addEdge("generate", END);
        // 映射表必须覆盖路由函数所有可能返回值
        workflow.addConditionalEdges("evaluate", edge_async(state -> {
            if (state.sufficient()) {
                return "ok";
            }
            return state.retryCount() < maxRetry ? "retry" : "fallback";
        }), Map.of("ok", "generate", "retry", "rewrite", "fallback", "generate"));
        this.graph = workflow.compile();
    }

    public RagResult chat(String question, List<Long> kbIds) {
        // 1.9.7 中单参 invoke(Map) 已弃用；返回 Optional 只解包一次
        RagState result = graph.invoke(
                Map.of(RagState.QUESTION, question, RagState.KB_IDS, kbIds),
                RunnableConfig.builder().build()).orElseThrow();
        return new RagResult(result.answer(), result.sources(), result.retryCount());
    }

    private Map<String, Object> retrieve(RagState state) {
        String query = state.rewrittenQuery().isBlank() ? state.question() : state.rewrittenQuery();
        List<RetrievedChunk> chunks = retrievalService.retrieve(query, state.keywords(), state.kbIds());
        List<Map<String, Object>> chunkMaps = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            String fileName = c.metadata().getOrDefault(MetadataKeys.FILE_NAME, "未知文档");
            chunkMaps.add(Map.of("index", i + 1, "text", c.text(), "file_name", fileName, "score", c.score()));
            context.append("[").append(i + 1).append("] (来源: ").append(fileName).append(")\n")
                    .append(c.text()).append("\n\n");
        }
        log.info("retrieve 完成, query={}, 命中 {} 段", query, chunks.size());
        return Map.of(RagState.CHUNKS, chunkMaps, RagState.CONTEXT, context.toString());
    }

    private Map<String, Object> evaluate(RagState state) {
        if (state.context().isBlank()) {
            return Map.of(RagState.SUFFICIENT, false);
        }
        String prompt = """
                判断下面的资料是否足以回答用户问题。只输出 JSON：{"sufficient": true} 或 {"sufficient": false}
                用户问题：%s
                资料：
                %s
                """.formatted(state.question(), state.context());
        boolean sufficient = false;
        try {
            JsonNode node = objectMapper.readTree(jsonChatModel.chat(prompt));
            sufficient = node.path("sufficient").asBoolean(false);
        } catch (Exception e) {
            log.warn("evaluate JSON 解析失败，按不足处理: {}", e.getMessage());
        }
        return Map.of(RagState.SUFFICIENT, sufficient);
    }

    private Map<String, Object> rewrite(RagState state) {
        String prompt = """
                上一次检索没有找到足以回答问题的资料。请改写检索查询，并提取 2-4 个中文关键词。
                只输出 JSON：{"query": "改写后的查询", "keywords": ["关键词1", "关键词2"]}
                原始问题：%s
                上次使用的查询：%s
                """.formatted(state.question(),
                state.rewrittenQuery().isBlank() ? state.question() : state.rewrittenQuery());
        String query = state.question();
        List<String> keywords = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(jsonChatModel.chat(prompt));
            query = node.path("query").asText(state.question());
            for (JsonNode k : node.path("keywords")) {
                keywords.add(k.asText());
            }
        } catch (Exception e) {
            log.warn("rewrite JSON 解析失败，用原问题重试: {}", e.getMessage());
        }
        // 重试计数在 rewrite 里递增：首次检索不计数，最多额外重试 maxRetry 次
        return Map.of(RagState.REWRITTEN_QUERY, query, RagState.KEYWORDS, keywords,
                RagState.RETRY_COUNT, state.retryCount() + 1);
    }

    private Map<String, Object> generate(RagState state) {
        String prompt = """
                你是企业知识库问答助手。请根据下面的编号资料回答用户问题。
                要求：
                1. 只依据资料回答，在相关句子末尾用 [n] 标注引用的资料编号。
                2. 资料不足时明确说明"知识库资料不足"，不要编造。
                3. 使用中文回答。
                用户问题：%s
                编号资料：
                %s
                """.formatted(state.question(),
                state.context().isBlank() ? "（未检索到资料）" : state.context());
        String answer = chatModel.chat(prompt);
        return Map.of(RagState.ANSWER, answer, RagState.SOURCES, state.chunks());
    }
}
```

### 5.5 步骤四：DTO 与 RagChatController

新建 `dto/RagChatRequest.java`：

```java
package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RagChatRequest(
        @NotBlank(message = "问题不能为空") String question,
        List<Long> kbIds   // 可选，缺省=全部可访问知识库
) {}
```

新建 `dto/RagResult.java`：

```java
package com.intern.backed.dto;

import java.util.List;
import java.util.Map;

public record RagResult(
        String answer,
        List<Map<String, Object>> sources,  // 每项: index/text/file_name/score
        int retryCount
) {}
```

新建 `controller/RagChatController.java`：

```java
package com.intern.backed.controller;

import com.intern.backed.auth.User;
import com.intern.backed.auth.UserContext;
import com.intern.backed.common.ApiResponse;
import com.intern.backed.dto.RagChatRequest;
import com.intern.backed.dto.RagResult;
import com.intern.backed.graph.RagWorkflow;
import com.intern.backed.kb.KbAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagChatController {
    private final RagWorkflow ragWorkflow;
    private final KbAccessService accessService;

    public RagChatController(RagWorkflow ragWorkflow, KbAccessService accessService) {
        this.ragWorkflow = ragWorkflow;
        this.accessService = accessService;
    }

    @PostMapping("/chat")
    public ApiResponse<RagResult> chat(@Valid @RequestBody RagChatRequest request) {
        User user = UserContext.currentUser();
        List<Long> kbIds = (request.kbIds() == null || request.kbIds().isEmpty())
                ? accessService.accessibleKbIds(user)
                : request.kbIds();
        // 用户显式指定的 kbId 也要逐个校验权限，防越权检索
        kbIds.forEach(id -> accessService.requireAccess(user, id));
        return new ApiResponse<>(200, "ok", ragWorkflow.chat(request.question(), kbIds), null);
    }
}
```

### 5.6 验证清单

```powershell
# 前提：阶段三已上传 test.md 且 READY
curl.exe -s -X POST http://localhost:8080/api/rag/chat -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "{\"question\":\"HashMap 底层结构是什么\"}"
```

- [ ] 回答带 [n] 引用，sources 数组含 file_name/text/score，与 [n] 对得上
- [ ] 问知识库里没有的内容 → 回答明示资料不足，retryCount 达到 2（观察后端日志 retrieve→rewrite 循环）
- [ ] 指定 kbIds 为无权访问的库 → 403
- [ ] 不配 SILICONFLOW_API_KEY 启动 → 日志提示跳过 rerank，问答仍正常
- [ ] 配了 key 但断网/错 key → 日志 warn 降级，问答仍正常
- [ ] `mvnw compile` 无弃用告警（invoke 用了双参新签名）

### 5.7 本阶段坑位预警（项目历史踩过）

1. **Channels.base() 无参重载在 1.9.7 已移除**，必须 `Channels.base(() -> 默认值)`；泛型推断不稳时加显式类型参数 `Channels.<List<String>>base(...)`
2. **invoke(Map) 单参已弃用**，用 `invoke(Map, RunnableConfig.builder().build())`；返回 Optional 只 orElseThrow 一次
3. **ChatModel 注入歧义**：容器里有 openAiChatModel 和 openAiJsonChatModel 两个候选，不加 @Qualifier 会报 NoUniqueBeanDefinitionException
4. **条件边映射表要覆盖所有路标**，漏一个运行时报 GraphStateException；带环图必须有 retryCount 退出保险（框架 recursionLimit 默认 25 是另一道防线）
5. **LLM 输出 JSON 可能带 markdown 围栏**：openAiJsonChatModel 已设 responseFormat=json_object 可避免；若仍解析失败，代码里已做降级（按不足/原问题处理），不要让解析异常炸掉整个图
6. **元数据过滤值类型**：入库存的是字符串 "1"，过滤必须 isIn(List<String>) 而不是 List<Long>，否则静默查空

---

## 6. 阶段五：前端（待你实现）

> 目录：`front/src`。现状：无路由（App.vue 手动 tab 切换），已有 pinia、markdown-it、dompurify、highlight.js。
> Vite 已配置 `/api` 代理到 8080，前端请求直接写相对路径即可。
> 实现顺序：装路由 → api 封装/auth store → router → 三个新页面 → App.vue 改造。

### 6.1 步骤一：安装路由并挂载

```powershell
cd E:\Project-Study\front
npm install vue-router@4
```

`main.js` 改为：

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
```

### 6.2 步骤二：api 封装 + auth store

新建 `src/utils/api.js`（统一带 token、统一解包 ApiResponse、401 跳登录）：

```js
import router from '@/router'

const BASE = ''  // 走 Vite 代理，相对路径即可

export async function api(path, options = {}) {
  const token = localStorage.getItem('token')
  const headers = { ...(options.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  // 上传 FormData 时不要手动设 Content-Type，浏览器会自动带 boundary
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  const res = await fetch(BASE + path, { ...options, headers })
  if (res.status === 401) {
    localStorage.removeItem('token')
    router.push('/login')
    throw new Error('未登录或登录已过期')
  }
  const json = await res.json()
  if (!res.ok || json.code !== 200) {
    throw new Error(json.message || `请求失败(${res.status})`)
  }
  return json.data
}
```

新建 `src/stores/auth.js`：

```js
import { defineStore } from 'pinia'
import { api } from '@/utils/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null'),
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
  },
  actions: {
    async login(username, password) {
      const data = await api('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('token', data.token)
      localStorage.setItem('user', JSON.stringify(data.user))
    },
    async logout() {
      try { await api('/api/auth/logout', { method: 'POST' }) } catch (e) { /* 忽略 */ }
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    },
  },
})
```

### 6.3 步骤三：路由与守卫

新建 `src/router/index.js`：

```js
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('@/views/LoginView.vue') },
  { path: '/', redirect: '/kb' },
  { path: '/kb', component: () => import('@/views/KbManageView.vue'), meta: { auth: true } },
  { path: '/kb/chat', component: () => import('@/views/KbChatView.vue'), meta: { auth: true } },
  // 旧页面保留，不强制登录
  { path: '/chat', component: () => import('@/views/ChatViews.vue') },
  { path: '/extract', component: () => import('@/views/ExtractView.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.auth && !localStorage.getItem('token')) {
    return '/login'
  }
})

export default router
```

注意：`/kb/chat` 要写在 `/kb` 之后或确保精确匹配（vue-router4 默认精确匹配，两者不冲突）。

### 6.4 步骤四：LoginView.vue

结构：居中卡片，用户名/密码输入 + 登录按钮 + 错误提示。核心逻辑：

```vue
<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push('/kb')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
```

模板自己写：表单 `@submit.prevent="submit"`，按钮 `:disabled="loading"`，页面底部提示种子账号 admin/admin123。

### 6.5 步骤五：KbManageView.vue（知识库 + 文档管理）

布局建议：左侧知识库列表（可新建/删除），右侧选中库的文档表格。

关键逻辑片段：

```js
import { api } from '@/utils/api'

const kbs = ref([])
const selectedKb = ref(null)
const docs = ref([])
let pollTimer = null

async function loadKbs() { kbs.value = await api('/api/kb') }

async function createKb(form) {
  await api('/api/kb', { method: 'POST', body: JSON.stringify(form) })
  await loadKbs()
}

async function selectKb(kb) {
  selectedKb.value = kb
  await loadDocs()
  startPolling()
}

async function loadDocs() {
  docs.value = await api(`/api/kb/${selectedKb.value.id}/documents`)
}

// 有 PROCESSING 状态时每 2s 轮询，全部终态后停止；组件卸载必须 clearInterval
function startPolling() {
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    await loadDocs()
    if (!docs.value.some(d => d.status === 'PROCESSING')) clearInterval(pollTimer)
  }, 2000)
}
onUnmounted(() => clearInterval(pollTimer))

async function upload(file) {
  const form = new FormData()
  form.append('file', file)
  await api(`/api/kb/${selectedKb.value.id}/documents`, { method: 'POST', body: form })
  await loadDocs()
  startPolling()
}

async function removeDoc(doc) {
  await api(`/api/kb/documents/${doc.id}`, { method: 'DELETE' })
  await loadDocs()
}
```

模板要点：
- 新建库表单：name + description + visibility 下拉（PRIVATE/DEPARTMENT/PUBLIC，附中文说明）
- 上传：`<input type="file" accept=".pdf,.doc,.docx,.md,.txt" @change="upload($event.target.files[0])">`
- 文档表格列：文件名/类型/大小/状态徽章(PROCESSING 蓝、READY 绿、FAILED 红可悬停看 errorMsg)/ chunkCount/删除按钮
- 删除前 `confirm()` 二次确认

### 6.6 步骤六：KbChatView.vue（问答 + 引用溯源）

布局建议：顶部知识库多选（checkbox 列表，不选=全部可访问），中部对话流，回答下方引用卡片。

关键逻辑：

```js
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
const md = new MarkdownIt()

const messages = ref([])   // { role, content, sources?, retryCount? }
const selectedKbIds = ref([])
const sending = ref(false)

async function send(question) {
  messages.value.push({ role: 'user', content: question })
  sending.value = true
  try {
    const data = await api('/api/rag/chat', {
      method: 'POST',
      body: JSON.stringify({ question, kbIds: selectedKbIds.value }),
    })
    messages.value.push({ role: 'assistant', content: data.answer, sources: data.sources, retryCount: data.retryCount })
  } catch (e) {
    messages.value.push({ role: 'assistant', content: `出错了：${e.message}` })
  } finally {
    sending.value = false
  }
}

function render(text) {
  return DOMPurify.sanitize(md.render(text || ''))
}
```

引用卡片模板要点：

```vue
<div v-if="msg.sources?.length" class="sources">
  <details v-for="s in msg.sources" :key="s.index">
    <summary>[{{ s.index }}] {{ s.file_name }}（相关度 {{ s.score.toFixed(3) }}）</summary>
    <p>{{ s.text }}</p>
  </details>
</div>
```

回答正文用 `v-html="render(msg.content)"`（已经 DOMPurify 消毒，与现有 ChatViews 做法一致）。
retryCount > 0 时可显示"已自动改写检索 n 次"徽章，体现自适应检索过程。

### 6.7 步骤七：App.vue 改造

把现有手动 tab 换成导航 + 路由出口，沿用现有蓝色主题（#2563eb）：

```vue
<script setup>
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
const auth = useAuthStore()
const router = useRouter()
async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <nav class="tabs">
    <router-link to="/kb">知识库管理</router-link>
    <router-link to="/kb/chat">知识库问答</router-link>
    <router-link to="/chat">流式对话</router-link>
    <router-link to="/extract">公文抽取</router-link>
    <span class="spacer" />
    <template v-if="auth.isLoggedIn">
      <span>{{ auth.user?.username }}（{{ auth.user?.department }}）</span>
      <button @click="logout">退出</button>
    </template>
    <router-link v-else to="/login">登录</router-link>
  </nav>
  <router-view />
</template>
```

样式自己补：`.tabs` 复用现有样式，`router-link-active` 加高亮，`.spacer { flex: 1 }` 把用户区推到右侧。

### 6.8 验证清单

- [ ] `npm run build` 无报错（残留 import 是历史高频坑，改完 App.vue 检查旧 import 是否清干净）
- [ ] 未登录访问 /kb 被重定向 /login；登录后跳回 /kb
- [ ] admin 登录 → 建库 → 上传 md → 状态徽章 2s 内从蓝变绿
- [ ] 问答页提问 → 回答带 [n]，点开引用卡片能看到原文片段与文件名
- [ ] 刷新页面登录态保持（localStorage）；退出后 token 清除
- [ ] 后端重启后旧 token 失效 → 前端自动跳登录页（401 处理生效）
- [ ] 旧 /chat、/extract 页面功能不受影响

---

## 7. 阶段六：端到端验证（待你执行）

### 7.1 建议补充的单元测试（backed/src/test）

| 测试类 | 测什么 | 思路 |
|---|---|---|
| RrfFusionTest | 两路重叠文档分数叠加、排序正确 | 把 Candidate/融合逻辑抽成可测的纯函数，或直接测 HybridRetrievalService（mock EmbeddingStore/JdbcTemplate） |
| KbAccessServiceTest | 权限矩阵全组合 | mock KbRepository.findAll 返回固定库列表，断言不同 User 的 accessibleKbs |
| IngestionSplitTest | 切分后每段都带 kb_id/doc_id/chunk_index | mock EmbeddingModel/EmbeddingStore，用真实 TextDocumentParser + 长文本验证 |

参考现有 `ExtractServiceTest` 的写法（Mockito 风格）。运行：`.\mvnw.cmd test`。

### 7.2 全链路手工验证脚本（按顺序）

1. `docker compose up -d` → 确认三张业务表
2. 启动 Ollama（bge-m3）与后端 → 确认 rag_embedding 表自动创建、种子账号日志
3. admin 登录 → 建 PRIVATE 库 A、DEPARTMENT(管理部) 库 B
4. 向 A 上传技术文档（md/pdf 各一）→ 轮询 READY → psql 核对向量行数 = chunk_count 之和
5. 问答：命中的问题带引用；库外问题走满重试后明示不足
6. demo 登录：看不到 A；建自己的库 C 上传文档；尝试指定 kbIds=[A] 问答 → 403
7. 删除文档 → 向量行数减少；删除知识库 → 该库向量全部清空
8. 去掉 SILICONFLOW_API_KEY 重启 → 问答仍正常（降级路径）
9. 前端完整走一遍 6.8 清单

### 7.3 回归项

- [ ] 旧流式对话（/api/chat SSE）正常
- [ ] 公文抽取（/api/extract）正常
- [ ] graph/Workflow.java 学习 demo 仍可独立运行（不受新代码影响）

---

## 8. 坑位速查表（本项目历史踩过，按阶段对照）

| 坑 | 现象 | 规避 |
|---|---|---|
| 普通 main 里写 "${VAR}" | Not enough variable values to expand | 占位符只在 Spring 解析 yaml 时生效；Java 代码用 System.getenv |
| LangChain4j 两条版本线 | Could not find artifact | 核心 1.23.0，集成模块 1.23.0-beta33，新增依赖前先查 maven-metadata.xml |
| deepseek-flash | 400 Model Not Exist | 有效模型只有 deepseek-chat / deepseek-reasoner |
| 系统空环境变量覆盖 .env | 401 auth header format | 系统/用户环境变量优先级高于 .env，排查三处来源 |
| optional: 导入静默跳过 | 占位符解析失败或默认值兼底 | 工作目录必须是 backed/；关键配置加启动自检 |
| Channels.base() 无参 | 编译错误 | 1.9.7 必须传 Supplier 默认值 |
| invoke(Map) 单参 | 弃用告警 | invoke(Map, RunnableConfig.builder().build())，orElseThrow 仅一次 |
| ChatModel 双候选 | NoUniqueBeanDefinitionException | @Qualifier("openAiChatModel") / ("openAiJsonChatModel") |
| @Async 自调用 | 异步变同步阻塞请求 | 跨 Bean 调用；异步线程里取不到 ThreadLocal 用户上下文 |
| 元数据存 Long | 过滤静默查空 | 元数据值统一字符串，过滤用 isIn(List<String>) |
| init.sql 不生效 | 表不存在 | 只在数据卷首次初始化执行；docker compose down -v 后重建（会丢数据，谨慎） |
| 编辑器未保存 | 改了还报同样错 | 编译器读磁盘文件；命令行直读磁盘确认 |
| Vite 残留 import | 编译/白屏 | 重构 App.vue 后检查旧组件 import 是否清理 |

---

## 9. 进阶方向（完成基础版后可选，面试加分项）

1. **流式问答**：把 generate 节点换成 StreamingChatModel + SSE，参考现有 DeepseekStreamChat 的写法；难点是图执行与流式推送的桥接
2. **会话记忆**：RunnableConfig.threadId + MemorySaver 检查点，同 threadId 多轮续跑；或给问答接 ChatMemory
3. **认证升级**：JWT 无状态 / Redis 会话；Spring Security 完整接入
4. **检索升级**：pg_trgm 相似度替代 ILIKE；zhparser 中文分词 + tsvector 全文检索；父子段落（small-to-big）检索
5. **入库升级**：入库进度百分比；重复文件哈希去重；文档版本更新（先删旧向量再入新）
6. **质量评估**：构造问答测试集，统计命中率/引用准确率；了解 RAGAS 指标（faithfulness、answer relevancy、context precision）
7. **可观测**：现有 traceId 贯穿入库异步线程（TaskDecorator 传递 MDC）；检索耗时/重试次数打点

面试讲述模板（STAR）：
- S：企业文档分散、检索靠人工，需要带权限隔离的内部知识问答
- T：负责 RAG 全链路：入库管道、混合检索、自适应重试、引用溯源
- A：重点讲 2-3 个决策：RRF 为什么不用分数相加；kb_id 过滤下推而非检索后过滤；rerank 降级设计；业务重试计数与 recursionLimit 双防线
- R：可量化结果（检索命中率提升、回答带可验证引用、越权访问 0 漏网）

---

*文档生成于本次改造会话；阶段一、二代码已在工作区并编译通过，阶段三至六参考代码均按 langchain4j 1.23.0 / langgraph4j 1.9.7 实际 API 编写，实现时以编译结果为准。*