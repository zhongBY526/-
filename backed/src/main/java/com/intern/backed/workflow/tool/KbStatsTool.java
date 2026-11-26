package com.intern.backed.workflow.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具 1：知识库统计（DB 查询工具）。
 * 意图为 STATS 时由工作流调用，直接查 rag_document/knowledge_base 聚合，不经过模型。
 */
@Slf4j
@Component
public class KbStatsTool {
    private final JdbcTemplate jdbc;

    public KbStatsTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> stats(List<Long> kbIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", "KbStatsTool");
        if (kbIds == null || kbIds.isEmpty()) {
            result.put("ok", false);
            result.put("message", "未指定知识库范围");
            return result;
        }
        try {
            String placeholders = String.join(",", Collections.nCopies(kbIds.size(), "?"));
            List<Map<String, Object>> perKb = jdbc.queryForList("""
                    SELECT k.id AS kb_id, k.name AS kb_name,
                           COUNT(d.id) AS doc_count,
                           COALESCE(SUM(CASE WHEN d.status = 'READY' THEN 1 ELSE 0 END), 0) AS ready_count,
                           COALESCE(SUM(CASE WHEN d.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count,
                           COALESCE(SUM(d.chunk_count), 0) AS chunk_total
                    FROM knowledge_base k LEFT JOIN rag_document d ON d.kb_id = k.id
                    WHERE k.id IN (%s)
                    GROUP BY k.id, k.name ORDER BY k.id
                    """.formatted(placeholders), kbIds.toArray());
            long docTotal = perKb.stream().mapToLong(r -> ((Number) r.get("doc_count")).longValue()).sum();
            long chunkTotal = perKb.stream().mapToLong(r -> ((Number) r.get("chunk_total")).longValue()).sum();
            result.put("ok", true);
            result.put("kbCount", perKb.size());
            result.put("docTotal", docTotal);
            result.put("chunkTotal", chunkTotal);
            result.put("perKb", perKb);
        } catch (Exception e) {
            // 工具异常兜底：不抛出中断工作流，返回失败标记由节点转成可读答复
            log.warn("KbStatsTool 查询失败: {}", e.getMessage());
            result.put("ok", false);
            result.put("message", "统计查询失败：" + e.getMessage());
        }
        return result;
    }

    /** 把统计结果渲染成给用户看的中文文本 */
    public String render(Map<String, Object> stats) {
        if (!Boolean.TRUE.equals(stats.get("ok"))) {
            return "知识库统计失败：" + stats.getOrDefault("message", "未知原因") + "。请稍后重试。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("知识库统计结果（共 ").append(stats.get("kbCount")).append(" 个库，")
                .append(stats.get("docTotal")).append(" 份文档，")
                .append(stats.get("chunkTotal")).append(" 个分块）：\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perKb = (List<Map<String, Object>>) stats.get("perKb");
        for (Map<String, Object> row : perKb) {
            sb.append("- ").append(row.get("kb_name"))
                    .append("：文档 ").append(row.get("doc_count"))
                    .append(" 份（就绪 ").append(row.get("ready_count"))
                    .append("，失败 ").append(row.get("failed_count"))
                    .append("），分块 ").append(row.get("chunk_total")).append("\n");
        }
        return sb.toString().strip();
    }
}