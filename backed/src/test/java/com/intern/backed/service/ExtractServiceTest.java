package com.intern.backed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intern.backed.config.ExtractAssistant;
import com.intern.backed.dto.ExtractResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractServiceTest {

    private ExtractService serviceReturning(String raw) {
        ExtractAssistant assistant = text -> raw;
        return new ExtractService(assistant, new ObjectMapper());
    }

    @Test
    void invalidJsonFallsBackToWarnings() {
        ExtractResult result = serviceReturning("抱歉，这段文本无法抽取").extract("原文", "t-1");

        assertNull(result.docType());
        assertNull(result.title());
        assertTrue(result.warnings().contains("结构化解析失败，请人工核对"));
    }

    @Test
    void stripsMarkdownCodeFence() {
        String raw = """
                ```json
                {"docType":"通知","title":"关于开展排查的通知","addressee":"各学院","signature":"学生工作处","date":"2026-11-01","warnings":[]}
                ```
                """;

        ExtractResult result = serviceReturning(raw).extract("原文", "t-2");

        assertEquals("通知", result.docType());
        assertEquals("关于开展排查的通知", result.title());
        assertEquals("2026-11-01", result.date());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unknownDocTypeAndBadDateAreDowngraded() {
        String raw = """
                {"docType":"公告","title":"标题","addressee":null,"signature":null,"date":"2026年11月1日","warnings":[]}
                """;

        ExtractResult result = serviceReturning(raw).extract("原文", "t-3");

        assertNull(result.docType());
        assertNull(result.date());
        assertTrue(result.warnings().contains("文种不在允许范围内，请人工核对"));
        assertTrue(result.warnings().contains("日期格式无法识别，请人工核对"));
    }
}
