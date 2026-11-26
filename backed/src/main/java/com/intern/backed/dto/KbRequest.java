package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KbRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "知识库名称过长")
        String name,
        @Size(max = 512, message = "描述过长")
        String description,
        @Pattern(regexp = "PRIVATE|DEPARTMENT|PUBLIC", message = "可见性必须为 PRIVATE/DEPARTMENT/PUBLIC")
        String visibility
) {}