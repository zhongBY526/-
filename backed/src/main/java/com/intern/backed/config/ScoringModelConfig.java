package com.intern.backed.config;

import com.intern.backed.retrieval.DashScopeScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class ScoringModelConfig {
    // 阿里云百炼（DashScope）rerank 使用原生协议，与 Jina/OpenAI 的 /rerank 报文不兼容，
    // 因此用自定义 DashScopeScoringModel，而不是 langchain4j-jina。
    // 未配置 QWEN_API_KEY 时不返回模型，检索链路通过 ObjectProvider 判空降级为 RRF 排序。
    @Bean
    public ScoringModel scoringModel(
            @Value("${rag.rerank.base-url}") String baseUrl,
            @Value("${rag.rerank.api-key}") String apiKey,
            @Value("${rag.rerank.model-name}") String modelName,
            @Value("${rag.rerank.timeout:30s}") Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("rag.rerank.api-key 为空，跳过 rerank 模型注册，检索降级为 RRF 排序");
            return null;
        }
        log.info("注册百炼 rerank 模型: baseUrl={}, model={}", baseUrl, modelName);
        return new DashScopeScoringModel(baseUrl, apiKey, modelName, timeout);
    }
}