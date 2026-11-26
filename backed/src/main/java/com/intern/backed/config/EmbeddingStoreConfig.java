package com.intern.backed.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingStoreConfig {
    // 持久化向量存储：docker compose 启动的 pgvector，表不存在时自动创建
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${rag.pgvector.host}") String host,
            @Value("${rag.pgvector.port}") int port,
            @Value("${rag.pgvector.database}") String database,
            @Value("${rag.pgvector.user}") String user,
            @Value("${rag.pgvector.password}") String password,
            @Value("${rag.pgvector.table}") String table,
            @Value("${rag.pgvector.dimension}") int dimension) {
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table(table)
                .dimension(dimension)
                .createTable(true)
                .build();
    }
}