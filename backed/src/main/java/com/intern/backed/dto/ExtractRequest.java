package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExtractRequest(
        @NotBlank(message = "text 不能为空")
        @Size(max = 10_000, message = "text 不能超过 10000 字")
        String text
) {
}