package com.intern.backed.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import dev.langchain4j.data.document.Metadata;

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

    //
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
    private Map<String, String> toStringMap(Map<String, Object> raw) {
        return raw.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> String.valueOf(e.getValue())));
    }
    private record KeywordRow(String embeddingId, String text, String metadataJson) {}
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
    // RRF类，用于存储检索结果的文本、元数据和RRF分数
    private static final class Candidate {
        // 检索结果的文本
        private final String text;
        // 检索结果的元数据
        private final Map<String, String> metadata;
        // RRF分数
        private double rrfScore;
        // 向量分支的余弦相似度；-1 表示该候选只被关键词分支命中，没有向量分
        private double vectorScore = -1;

        Candidate(String text, Map<String, String> metadata) {
            this.text = text;
            this.metadata = metadata;
        }

        void addRrf(int rank) {
            rrfScore += 1.0 / (RRF_K + rank);
        }

        void vectorScore(double score) { this.vectorScore = score; }

        String text() { return text; }
        Map<String, String> metadata() { return metadata; }
        double rrfScore() { return rrfScore; }
        double vectorScore() { return vectorScore; }
    }
    public List<RetrievalChunk> retrieve(String query, List<String> keywords,List<Long>kbIds) {
        if(kbIds==null ||kbIds.isEmpty()){
            return List.of();
        }
        List<String> kbStrings=kbIds.stream().map(String::valueOf
        ).toList();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        EmbeddingSearchRequest request=EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .minScore(minScore)
                .maxResults(vectorTopK)
                .filter(metadataKey(MetadataKeys.KB_ID).isIn(kbStrings))
                .build();
        List<EmbeddingMatch<TextSegment>> matches=embeddingStore.search(request).matches();
        for(int rank = 0; rank < matches.size(); rank++) {
            EmbeddingMatch<TextSegment> m = matches.get(rank);
            Candidate candidate = candidates.computeIfAbsent(m.embeddingId(),
                    id -> new Candidate(m.embedded().text(), toStringMap(m.embedded().metadata().toMap())));
            candidate.addRrf(rank + 1);
            candidate.vectorScore(m.score()); // 保留真实余弦相似度，供展示用
        }
        List<KeywordRow> rows = keywordSearch(keywords, kbStrings);
        for (int rank = 0; rank < rows.size(); rank++) {
            KeywordRow row = rows.get(rank);
            candidates.computeIfAbsent(row.embeddingId(),
                            id -> new Candidate(row.text(), parseMetadata(row.metadataJson())))
                    .addRrf(rank + 1);
        }
        List<Candidate> fused = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed())
                .limit(vectorTopK)
                .toList();
        if (fused.isEmpty()) {
            return List.of();
        }
        log.info(String.valueOf(matches.get(0).score()));
        ScoringModel reranker = scoringModelProvider.getIfAvailable();
        if (reranker != null) {
            try {
                List<TextSegment> segments = fused.stream()
                        .map(c -> TextSegment.from(c.text(), Metadata.from(c.metadata())))
                        .toList();
                List<Double> scores = reranker.scoreAll(segments, query).content();
                List<RetrievalChunk> reranked = new ArrayList<>();
                for (int i = 0; i < fused.size(); i++) {
                    reranked.add(new RetrievalChunk(fused.get(i).text(), fused.get(i).metadata(),
                            scores.get(i), RetrievalChunk.ScoreType.RERANK));
                }
                reranked.sort(Comparator.comparingDouble(RetrievalChunk::score).reversed());
                log.info(String.valueOf(reranked.get(0).score()));
                return reranked.stream().limit(finalTopK).toList();
            } catch (Exception e) {
                log.warn("rerank 调用失败，降级用 RRF 排序: {}", e.getMessage());
            }
        }
        // 没有 rerank 时优先展示向量余弦相似度；仅关键词命中的候选没有余弦分，退回 RRF 融合分
        return fused.stream().limit(finalTopK)
                .map(c -> c.vectorScore() >= 0
                        ? new RetrievalChunk(c.text(), c.metadata(), c.vectorScore(), RetrievalChunk.ScoreType.VECTOR)
                        : new RetrievalChunk(c.text(), c.metadata(), c.rrfScore(), RetrievalChunk.ScoreType.RRF))
                .toList();
    }
 }
