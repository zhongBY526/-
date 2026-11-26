package com.intern.backed.dto;

import java.util.List;
import java.util.Map;

public record RagResult(
        String answer,
        List<Map<String, Object>> sources,  // 每项: index/text/file_name/score
        int retryCount
) {}
