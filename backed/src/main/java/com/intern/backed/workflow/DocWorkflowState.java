package com.intern.backed.workflow;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

import static com.intern.backed.graph.RagState.SOURCES;


/** 公文工作流图状态：意图识别→检索→生成→格式校验（QA/DRAFT），STATS/CHAT 走工具分支 */
public class DocWorkflowState extends AgentState {
    public DocWorkflowState(Map<String, Object> initData) {
        super(initData);
    }
  //用户问题
    public static final String QUESTION = "question";
    public static final String KB_IDS = "kbIds";
    //意图
    public static final String INTENT = "intent";
    //检索关键词
    public static final String KEYWORDS = "keywords";
    //检索结果
    public static final String CHUNKS = "chunks";
    //上下文
    public static final String CONTEXT = "context";
    //草稿
    public static final String DRAFT = "draft";
    //格式校验报告
    public static final String FORMAT_REPORT = "formatReport";
    //来源
    public static final String SOURCES = "sources";
    //步骤
    public static final String STEPS = "steps";
    //错误
    public static final String ERROR = "error";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(QUESTION, Channels.base(() -> "")),
            Map.entry(KB_IDS, Channels.<List<Long>>base(() -> List.of())),
            Map.entry(INTENT, Channels.base(() -> "")),
            Map.entry(KEYWORDS, Channels.<List<String>>base(() -> List.of())),
            Map.entry(CHUNKS, Channels.<List<Map<String, Object>>>base(() -> List.of())),
            Map.entry(CONTEXT, Channels.base(() -> "")),
            Map.entry(DRAFT, Channels.base(() -> "")),
            Map.entry(FORMAT_REPORT, Channels.<Map<String, Object>>base(() -> Map.of())),
            Map.entry(SOURCES, Channels.<List<Map<String, Object>>>base(() -> List.of())),
            Map.entry(STEPS, Channels.<List<Map<String, Object>>>base(() -> List.of())),
            Map.entry(ERROR, Channels.base(() -> ""))
    );

    public String question() { return this.<String>value(QUESTION).orElse(""); }
    public List<Long> kbIds() { return this.<List<Long>>value(KB_IDS).orElse(List.of()); }
    public String intent() { return this.<String>value(INTENT).orElse(""); }
    public List<String> keywords() { return this.<List<String>>value(KEYWORDS).orElse(List.of()); }
    public List<Map<String, Object>> chunks() { return this.<List<Map<String, Object>>>value(CHUNKS).orElse(List.of()); }
    public String context() { return this.<String>value(CONTEXT).orElse(""); }
    public String draft() { return this.<String>value(DRAFT).orElse(""); }
    public Map<String, Object> formatReport() { return this.<Map<String, Object>>value(FORMAT_REPORT).orElse(Map.of()); }
    public List<Map<String, Object>> sources() { return this.<List<Map<String, Object>>>value(SOURCES).orElse(List.of()); }
    public List<Map<String, Object>> steps() { return this.<List<Map<String, Object>>>value(STEPS).orElse(List.of()); }
    public String error() { return this.<String>value(ERROR).orElse(""); }

    /** 追加一条节点执行轨迹（节点串行执行，直接覆盖式追加即可） */
    public List<Map<String, Object>> appendStep(String node, String status, String detail) {
        List<Map<String, Object>> next = new java.util.ArrayList<>(steps());
        next.add(Map.of("node", node, "status", status, "detail", detail,
                "ts", System.currentTimeMillis()));
        return next;
    }
}