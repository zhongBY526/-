package com.intern.backed.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.context.annotation.Configuration;


import java.util.List;
import java.util.Map;

public class RagState extends AgentState {
    public RagState(Map<String, Object> initData) {
        super(initData);
    }
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
