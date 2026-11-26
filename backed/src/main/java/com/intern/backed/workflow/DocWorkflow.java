package com.intern.backed.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intern.backed.retrieval.HybridRetrievalService;
import com.intern.backed.retrieval.MetadataKeys;
import com.intern.backed.retrieval.RetrievalChunk;
import com.intern.backed.workflow.skill.OfficialDocFormatSkill;
import com.intern.backed.workflow.tool.HttpTimeTool;
import com.intern.backed.workflow.tool.KbStatsTool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 公文工作流图：
 *   START → classify(意图识别)
 *   classify --条件分支--> retrieve(QA/DRAFT) | stats(STATS工具) | chat(CHAT工具)
 *   retrieve → generate(生成) → formatCheck(公文格式校验 Skill) → END
 *   stats/chat → END
 * 每个节点自带异常兜底：失败写入 ERROR/降级文案，不抛异常中断整个图。
 */
@Slf4j
@Component
public class DocWorkflow {
    private final HybridRetrievalService retrievalService;
    private final OpenAiChatModel jsonChatModel;
    private final OpenAiChatModel workflowChatModel;
    private final OfficialDocFormatSkill formatSkill;
    private final KbStatsTool kbStatsTool;
    private final HttpTimeTool httpTimeTool;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CompiledGraph<DocWorkflowState> graph;

    public DocWorkflow(HybridRetrievalService retrievalService,
                       @Qualifier("openAiJsonChatModel") OpenAiChatModel jsonChatModel,
                       @Qualifier("workflowChatModel") OpenAiChatModel workflowChatModel,
                       OfficialDocFormatSkill formatSkill,
                       KbStatsTool kbStatsTool,
                       HttpTimeTool httpTimeTool) throws GraphStateException {
        this.retrievalService = retrievalService;
        this.jsonChatModel = jsonChatModel;
        this.workflowChatModel = workflowChatModel;
        this.formatSkill = formatSkill;
        this.kbStatsTool = kbStatsTool;
        this.httpTimeTool = httpTimeTool;
        var workflow = new StateGraph<>(DocWorkflowState.SCHEMA, DocWorkflowState::new);
        workflow.addNode("classify", node_async(this::classify));
        workflow.addNode("retrieve", node_async(this::retrieve));
        workflow.addNode("generate", node_async(this::generate));
        workflow.addNode("formatCheck", node_async(this::formatCheck));
        workflow.addNode("stats", node_async(this::stats));
        workflow.addNode("chat", node_async(this::chat));
        workflow.addEdge(START, "classify");
        // 条件分支：意图路由，映射表覆盖路由函数全部返回值
        workflow.addConditionalEdges("classify", edge_async(DocWorkflowState::intent),
                Map.of("QA", "retrieve", "DRAFT", "retrieve", "STATS", "stats", "CHAT", "chat"));
        workflow.addEdge("retrieve", "generate");
        workflow.addEdge("generate", "formatCheck");
        workflow.addEdge("formatCheck", END);
        workflow.addEdge("stats", END);
        workflow.addEdge("chat", END);
        this.graph = workflow.compile();
    }

    public DocWorkflowState run(String question, List<Long> kbIds) {
        return graph.invoke(Map.of(DocWorkflowState.QUESTION, question, DocWorkflowState.KB_IDS, kbIds),
                RunnableConfig.builder().build()).orElseThrow();
    }

    /** 节点 1：意图识别（JSON 模型），同时产出检索关键词；失败兜底为 QA */
    private Map<String, Object> classify(DocWorkflowState state) {
        String prompt = """
                判断用户输入的意图，只输出 JSON：{"intent": "QA|DRAFT|STATS|CHAT", "keywords": ["关键词1", "关键词2"]}
                分类标准：
                - QA：查询知识库中的政策法规、公文规定等事实性问题
                - DRAFT：要求起草、撰写、拟写公文（通知/报告/请示/函等）
                - STATS：询问知识库文档数量、入库情况、分块统计等
                - CHAT：问候、闲聊、询问当前时间等与知识库内容无关的输入
                keywords 为 2-4 个中文检索关键词（STATS/CHAT 可为空数组）。
                用户输入：%s
                """.formatted(state.question());
        String intent = "QA";
        List<String> keywords = new ArrayList<>();
        String status = "ok";
        try {
            JsonNode node = objectMapper.readTree(jsonChatModel.chat(prompt));
            String raw = node.path("intent").asText("QA").toUpperCase();
            if (List.of("QA", "DRAFT", "STATS", "CHAT").contains(raw)) {
                intent = raw;
            }
            for (JsonNode k : node.path("keywords")) {
                keywords.add(k.asText());
            }
        } catch (Exception e) {
            // 异常兜底：意图识别失败按 QA 处理，用原问题检索，流程不中断
            log.warn("classify 失败，兜底为 QA: {}", e.getMessage());
            status = "fallback";
        }
        return Map.of(DocWorkflowState.INTENT, intent, DocWorkflowState.KEYWORDS, keywords,
                DocWorkflowState.STEPS, state.appendStep("classify", status, "意图=" + intent));
    }

    /** 节点 2：混合检索（复用 RAG 的向量+关键字+RRF+rerank 链路） */
    private Map<String, Object> retrieve(DocWorkflowState state) {
        try {
            List<RetrievalChunk> chunks = retrievalService.retrieve(state.question(), state.keywords(), state.kbIds());
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
            return Map.of(DocWorkflowState.CHUNKS, chunkMaps, DocWorkflowState.CONTEXT, context.toString(),
                    DocWorkflowState.STEPS, state.appendStep("retrieve", "ok", "命中 " + chunks.size() + " 段"));
        } catch (Exception e) {
            log.warn("retrieve 失败: {}", e.getMessage());
            return Map.of(DocWorkflowState.ERROR, "检索失败：" + e.getMessage(),
                    DocWorkflowState.STEPS, state.appendStep("retrieve", "error", e.getMessage()));
        }
    }

    /** 节点 3：生成。DRAFT 起草公文 / QA 依据资料回答；空上下文确定性拒答；模型失败降级文案 */
    private Map<String, Object> generate(DocWorkflowState state) {
        if (state.context().isBlank()) {
            String refusal = "知识库资料不足，无法回答该问题。本次未检索到任何相关资料，请确认问题在知识库覆盖范围内，或补充相关文档后重试。";
            return Map.of(DocWorkflowState.DRAFT, refusal, DocWorkflowState.SOURCES, List.of(),
                    DocWorkflowState.STEPS, state.appendStep("generate", "refused", "未检索到资料，确定性拒答"));
        }
        String prompt = "DRAFT".equals(state.intent()) ? """
                你是机关公文起草助手。请根据用户要求和编号资料起草一份公文，只输出公文全文，不要输出解释。
                格式要求（依据《党政机关公文处理工作条例》）：
                1. 第一行为标题：发文机关+事由+文种（如"××局关于××的通知"），不超过50字，末尾不加标点。
                2. 第二行顶格写主送机关，以"："结尾（可用"各有关部门："）。
                3. 正文结构层次序数依次用"一、""（一）""1.""（1）"；引用资料内容在句末用 [n] 标注编号。
                4. 只依据编号资料撰写，资料未覆盖的内容不得编造；资料不足处在文中注明"（待补充）"。
                5. 文末落款：发文机关名称，下一行成文日期（用阿拉伯数字，如 2026年11月25日）。
                6. 请示类须以"妥否，请批示"作结，且不得夹带报告事项。
                用户要求：%s
                编号资料：
                %s
                """.formatted(state.question(), state.context())
                : """
                你是企业知识库问答助手。请根据下面的编号资料回答用户问题。
                要求：
                1. 只依据资料回答，在相关句子末尾用 [n] 标注引用的资料编号。
                2. 资料不足时明确说明"知识库资料不足"，严禁用自身知识编造。
                3. 使用中文回答。
                用户问题：%s
                编号资料：
                %s
                """.formatted(state.question(), state.context());
        try {
            String draft = workflowChatModel.chat(prompt);
            return Map.of(DocWorkflowState.DRAFT, draft, DocWorkflowState.SOURCES, state.chunks(),
                    DocWorkflowState.STEPS, state.appendStep("generate", "ok", "生成 " + draft.length() + " 字"));
        } catch (Exception e) {
            // 异常兜底：模型调用失败给出降级文案并记录 ERROR，任务转 FAILED 可重跑
            log.warn("generate 失败: {}", e.getMessage());
            return Map.of(DocWorkflowState.DRAFT, "生成失败，请稍后重试或重新提交任务。",
                    DocWorkflowState.ERROR, "生成失败：" + e.getMessage(),
                    DocWorkflowState.STEPS, state.appendStep("generate", "error", e.getMessage()));
        }
    }

    /** 节点 4：公文格式校验 Skill。仅 DRAFT 校验，QA 标记跳过 */
    private Map<String, Object> formatCheck(DocWorkflowState state) {
        if (!"DRAFT".equals(state.intent())) {
            return Map.of(DocWorkflowState.FORMAT_REPORT, Map.of("skipped", true, "passed", true),
                    DocWorkflowState.STEPS, state.appendStep("formatCheck", "skipped", "非公文起草任务，跳过格式校验"));
        }
        Map<String, Object> report = formatSkill.checkAsMap(state.draft());
        String detail = "score=" + report.get("score") + ", passed=" + report.get("passed");
        return Map.of(DocWorkflowState.FORMAT_REPORT, report,
                DocWorkflowState.STEPS, state.appendStep("formatCheck", "ok", detail));
    }

    /** 工具分支 1：知识库统计（DB 工具，不经模型） */
    private Map<String, Object> stats(DocWorkflowState state) {
        Map<String, Object> result = kbStatsTool.stats(state.kbIds());
        String text = kbStatsTool.render(result);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        return Map.of(DocWorkflowState.DRAFT, text,
                DocWorkflowState.STEPS, state.appendStep("stats", ok ? "ok" : "fallback",
                        "KbStatsTool " + (ok ? "查询成功" : "查询失败已兜底")));
    }

    /** 工具分支 2：闲聊/时间（HTTP 工具，失败降级答复） */
    private Map<String, Object> chat(DocWorkflowState state) {
        Map<String, Object> time = httpTimeTool.now();
        boolean ok = Boolean.TRUE.equals(time.get("ok"));
        String text = ok
                ? "你好！我是公文工作流助手。当前服务器时间：" + time.get("now")
                    + "（经 HTTP 工具获取）。可以让我查询知识库政策、起草公文或统计入库情况。"
                : "你好！我是公文工作流助手。时间服务暂时不可用（已重试并兜底），"
                    + "不影响其他功能：可以让我查询知识库政策、起草公文或统计入库情况。";
        return Map.of(DocWorkflowState.DRAFT, text,
                DocWorkflowState.STEPS, state.appendStep("chat", ok ? "ok" : "fallback",
                        "HttpTimeTool " + (ok ? "调用成功" : "调用失败已兜底")));
    }
}