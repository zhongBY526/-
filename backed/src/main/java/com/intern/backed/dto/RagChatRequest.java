package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record RagChatRequest(
        @NotBlank(message = "问题不能为空") String question,
        List<Long> kbIds   // 可选，缺省=全部可访问知识库
) {}
