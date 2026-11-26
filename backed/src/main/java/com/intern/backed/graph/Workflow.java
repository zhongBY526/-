package com.intern.backed.graph;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class Workflow {
    public static void main(String[] args) throws Exception {
        //创建一个工作流
        var workflow = new StateGraph<>(
                State.SCHEMA,
                State::new
        );
        //添加检索节点
        workflow.addNode("retrieve", node_async(state -> {
            int count = state.retryCount() + 1;
            System.out.println("Retrying... " + count);
            String question = state.question();
            String context;
            //模拟第一次检索不足
            if (count == 1) {
                context = "Java HashMap 是一种 Map 集合。";
            } else {
                context = """
                        Java HashMap 基于哈希表实现。
                        JDK 8 中底层结构为数组、链表和红黑树。
                        当链表长度达到一定条件时，可能树化为红黑树。
                        """;
            }
            return Map.of(State.CONTEXT, context, State.RETRY_COUNT, count);
        }));
        workflow.addNode("evaluate", node_async(state -> {
            String context = state.context();
            boolean sufficient =
                    context.contains("数组")
                            && context.contains("红黑树");

            System.out.println("资料是否足够：" + sufficient);
            return Map.of(
                    State.SUFFICIENT,
                    sufficient
            );
        }));
        workflow.addNode("generate", node_async(state -> {
            String prompt = """
                    你是 Java 技术助手。

                    请根据下面的知识库资料回答用户问题。
                    用户问题：
                    %s
                    知识库资料：
                    %s
                    请使用中文回答。
                    """.formatted(
                    state.question(),
                    state.context()
            );
            String apiKey = env("DEEPSEEK_API_KEY", null);
            if (apiKey == null) {
                throw new IllegalStateException("DEEPSEEK_API_KEY 未配置，请设置环境变量或在 backed/.env 中填写");
            }
            String answer = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(env("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
                    .modelName(env("DEEPSEEK_MODEL", "deepseek-chat"))
                    .build()
                    .chat(prompt);
            return Map.of(
                    State.ANSWER,
                    answer
            );
        }));
        workflow.addEdge(START, "retrieve");
        workflow.addEdge("retrieve", "evaluate");
        workflow.addEdge("generate",END);
        workflow.addConditionalEdges("evaluate",edge_async(state->{
            if(state.sufficient()){
                return "ok";
            }
            return state.retryCount()<3?"retry":"fallback";
        }),Map.of("ok","generate"
        ,"retry","retrieve"
        ,"fallback","generate"));
        var graph = workflow.compile();
        var result = graph.invoke(Map.of(State.QUESTION, "Java HashMap 是什么？"), RunnableConfig.builder().build()).orElseThrow();
        System.out.println(result.answer());
    }

    //普通 main 方法不走 Spring，占位符不会被解析，需手动读环境变量并兜底加载 .env
    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        for (String path : new String[]{".env", "backed/.env"}) {
            Path file = Path.of(path);
            if (Files.exists(file)) {
                Properties props = new Properties();
                try (var reader = Files.newBufferedReader(file)) {
                    props.load(reader);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                String v = props.getProperty(key);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return defaultValue;
    }

}
