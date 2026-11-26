package com.intern.backed.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）文本重排序模型。
 * 百炼的 rerank 是 DashScope 原生协议（{@code /api/v1/services/rerank/text-rerank/text-rerank}），
 * 路径、请求体、响应体都不同于 Jina/OpenAI 的 {@code /rerank}，
 */
public class DashScopeScoringModel implements ScoringModel {

    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public DashScopeScoringModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + RERANK_PATH;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeout = timeout;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", modelName,
                    "input", Map.of(
                            "query", query,
                            "documents", segments.stream().map(TextSegment::text).toList()),
                    "parameters", Map.of("return_documents", false));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + abbreviate(response.body()));
            }
            JsonNode results = objectMapper.readTree(response.body()).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new IllegalStateException("响应缺少 output.results: " + abbreviate(response.body()));
            }
            // 百炼按 relevance_score 降序返回，index 才是原始文档位置：
            // 必须按 index 回填，否则分数会错配到别的片段上
            List<Double> scores = new ArrayList<>(Collections.nCopies(segments.size(), 0.0));
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < scores.size()) {
                    scores.set(index, item.path("relevance_score").asDouble(0.0));
                }
            }
            return Response.from(scores);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百炼 rerank 调用被中断", e);
        } catch (Exception e) {
            // 交给 HybridRetrievalService 的 catch 降级为 RRF 排序
            throw new IllegalStateException("百炼 rerank 调用失败: " + e.getMessage(), e);
        }
    }
    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }
}