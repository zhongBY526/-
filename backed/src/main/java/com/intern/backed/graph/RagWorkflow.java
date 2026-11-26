package com.intern.backed.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intern.backed.dto.RagResult;
import com.intern.backed.retrieval.HybridRetrievalService;
import com.intern.backed.retrieval.MetadataKeys;
import com.intern.backed.retrieval.RetrievalChunk;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
@Component
public class RagWorkflow {
    private final HybridRetrievalService retrievalService;
    private final StreamingChatModel chatModel;
    private final OpenAiChatModel jsonChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRetry;
    private final CompiledGraph<RagState> graph;
    private final Executor ragChatExecutor;

    public RagWorkflow(HybridRetrievalService retrievalService,
                       @Qualifier("openAiStreamingChatModel") StreamingChatModel chatModel,
                       @Qualifier("openAiJsonChatModel") OpenAiChatModel jsonChatModel,
                       @Value("${rag.retry.max}") int maxRetry,
                       @Qualifier("ragChatExecutor") Executor ragChatExecutor) throws GraphStateException {
        this.ragChatExecutor = ragChatExecutor;
        this.retrievalService = retrievalService;
        this.chatModel = chatModel;
        this.jsonChatModel = jsonChatModel;
        this.maxRetry = maxRetry;
        var workflow = new StateGraph<>(RagState.SCHEMA, RagState::new);
        workflow.addNode("retrieve", node_async(this::retrieve));
        workflow.addNode("evaluate", node_async(this::evaluate));
        workflow.addNode("rewrite", node_async(this::rewrite));
        workflow.addNode("generate", AsyncNodeActionWithConfig.node_async(this::generate));
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

public RagResult chat(String question, List<Long> kbIds) {
    // 1.9.7 中单参 invoke(Map) 已弃用；返回 Optional 只解包一次
    RagState result = graph.invoke(
            Map.of(RagState.QUESTION, question, RagState.KB_IDS, kbIds),
            RunnableConfig.builder().build()).orElseThrow();
    return new RagResult(result.answer(), result.sources(), result.retryCount());
}
private Map<String, Object> retrieve(RagState state) {
    String query = state.rewrittenQuery().isBlank() ? state.question() : state.rewrittenQuery();
    List<RetrievalChunk> chunks = retrievalService.retrieve(query, state.keywords(), state.kbIds());
    List<Map<String, Object>> chunkMaps = new ArrayList<>();
    StringBuilder context = new StringBuilder();
    for (int i = 0; i < chunks.size(); i++) {
        RetrievalChunk c = chunks.get(i);
        String fileName = c.metadata().getOrDefault(MetadataKeys.FILE_NAME, "未知文档");
        chunkMaps.add(Map.of("index", i + 1, "text", c.text(), "file_name", fileName,
                "chunk_index", c.metadata().getOrDefault(MetadataKeys.CHUNK_INDEX, "0"),
                "score", c.score(), "score_type", c.scoreType().name().toLowerCase()));
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

private Map<String, Object> generate(RagState state, RunnableConfig config) {
    SseEmitter emitter = (SseEmitter) config.metadata("sse").orElse(null);
    // 检索彻底空手而归：确定性拒答，不调模型，杜绝编造
    if (state.context().isBlank()) {
        String refusal = "知识库资料不足，无法回答该问题。本次未检索到任何相关资料，请确认问题在知识库覆盖范围内，或补充相关文档后重试。";
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("sources")
                        .data(Map.of("sources", List.of(), "retryCount", state.retryCount()),
                                MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("token")
                        .data(Map.of("delta", refusal), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.info("SSE refusal send stopped: {}", e.toString());
            }
        }
        return Map.of(RagState.ANSWER, refusal, RagState.SOURCES, List.of());
    }

    // 重试耗尽仍判资料不足：用拒答导向 prompt，只允许说明缺口，不允许用模型自身知识作答
    String prompt = state.sufficient() ? """
                你是企业知识库问答助手。请根据下面的编号资料回答用户问题。
                要求：
                1. 只依据资料回答，在相关句子末尾用 [n] 标注引用的资料编号。
                2. 资料不足时明确说明"知识库资料不足"，不要编造。
                3. 使用中文回答。
                用户问题：%s
                编号资料：
                %s
                """.formatted(state.question(), state.context())
            : """
                检索到的资料已被判定不足以回答用户问题。请明确告知用户"知识库资料不足，无法回答该问题"，
                并简要说明缺少哪方面的资料。严禁使用你自己的知识编造答案，严禁猜测。
                用户问题：%s
                已检索到的资料（仅供参考缺口）：
                %s
                """.formatted(state.question(), state.context());

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
        if (!latch.await(330, TimeUnit.SECONDS)) {
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
}

