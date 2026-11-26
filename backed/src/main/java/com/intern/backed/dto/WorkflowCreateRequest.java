package com.intern.backed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WorkflowCreateRequest(
        @NotBlank(message = "任务内容不能为空")
        @Size(max = 1024, message = "任务内容过长")
        String question,
        List<Long> kbIds   // 可选，缺省=全部可访问知识库
) {}