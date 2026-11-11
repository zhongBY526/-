package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        String userId,
        String conversationId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 不能超过 4000 字")
        String message,
        @NotBlank(message = "style 不能为空")
        @Pattern(regexp = "严谨|温和|简洁", message = "style 仅支持 严谨/温和/简洁")
        String style
) {}
