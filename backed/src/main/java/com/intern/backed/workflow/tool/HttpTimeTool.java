package com.intern.backed.workflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具 2：HTTP 时间工具。
 * 调用本服务公开端点 /api/public/now 演示真实 HTTP 工具调用：3 秒超时 + 1 次重试，
 * 全部失败时返回失败标记（异常兜底），由节点降级为不含时间的答复，工作流不中断。
 */
@Slf4j
@Component
public class HttpTimeTool {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public HttpTimeTool(@Value("${workflow.time-tool.url:http://localhost:8080/api/public/now}") String url) {
        this.url = url;
    }

    public Map<String, Object> now() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", "HttpTimeTool");
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode node = mapper.readTree(resp.body());
                String now = node.path("data").path("now").asText("");
                if (resp.statusCode() == 200 && !now.isBlank()) {
                    result.put("ok", true);
                    result.put("now", now);
                    result.put("attempt", attempt);
                    return result;
                }
                last = new IllegalStateException("HTTP " + resp.statusCode());
            } catch (Exception e) {
                last = e;
                log.warn("HttpTimeTool 第 {} 次调用失败: {}", attempt, e.getMessage());
            }
        }
        result.put("ok", false);
        result.put("message", "时间服务不可用：" + (last == null ? "未知原因" : last.getMessage()));
        return result;
    }
}