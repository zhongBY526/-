package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WorkflowConfirmRequest(
        @NotBlank(message = "确认动作不能为空")
        @Pattern(regexp = "CONFIRM|REJECT", message = "动作必须为 CONFIRM/REJECT")
        String action,
        @Size(max = 512, message = "备注过长")
        String comment
) {}