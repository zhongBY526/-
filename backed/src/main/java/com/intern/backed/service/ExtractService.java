package com.intern.backed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intern.backed.config.ExtractAssistant;
import com.intern.backed.dto.ExtractResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ExtractService {

    private static final Set<String> DOC_TYPES =
            Set.of("通知", "请示", "报告", "函", "纪要", "其他");

    private final ExtractAssistant assistant;
    private final ObjectMapper objectMapper;

    public ExtractService(ExtractAssistant assistant, ObjectMapper objectMapper) {
        this.assistant = assistant;
        this.objectMapper = objectMapper;
    }

    public ExtractResult extract(String text, String traceId) {
        String raw = assistant.chat(text);
        if (raw == null) {
            raw = "";
        }
        String json = raw.trim();

        // 模型偶尔会包一层 Markdown 代码围栏，先剥掉
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }

        ExtractResult parsed;
        try {
            parsed = objectMapper.readValue(json, ExtractResult.class);
        } catch (Exception e) {
            log.warn("extract parse failed, traceId={}, raw={}", traceId,
                    raw.length() > 300 ? raw.substring(0, 300) : raw);
            return new ExtractResult(null, null, null, null, null,
                    List.of("结构化解析失败，请人工核对"));
        }

        List<String> warnings = new ArrayList<>();
        if (parsed.warnings() != null) {
            warnings.addAll(parsed.warnings());
        }

        String docType = parsed.docType();
        if (docType == null || docType.isBlank()) {
            docType = null;
            warnings.add("文种无法识别，请人工核对");
        } else {
            docType = docType.trim();
            if (!DOC_TYPES.contains(docType)) {
                docType = null;
                warnings.add("文种不在允许范围内，请人工核对");
            }
        }

        String title = parsed.title() == null ? null : parsed.title().trim();
        if (title == null || title.isEmpty()) {
            title = null;
            warnings.add("标题无法识别，请人工核对");
        }

        String addressee = parsed.addressee() == null ? null : parsed.addressee().trim();
        if (addressee != null && addressee.isEmpty()) {
            addressee = null;
        }

        String signature = parsed.signature() == null ? null : parsed.signature().trim();
        if (signature != null && signature.isEmpty()) {
            signature = null;
        }

        String date = parsed.date() == null ? null : parsed.date().trim();
        if (date == null) {
            warnings.add("日期无法识别，请人工核对");
        } else {
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                date = null;
                warnings.add("日期格式无法识别，请人工核对");
            }
        }

        return new ExtractResult(docType, title, addressee, signature, date, warnings);
    }
}