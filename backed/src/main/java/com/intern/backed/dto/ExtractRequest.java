package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;

public record ExtractRequest(
        @NotBlank(message = "text 不能为空")
        String text
) {
}