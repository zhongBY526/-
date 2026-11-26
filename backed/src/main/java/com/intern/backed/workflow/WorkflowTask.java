package com.intern.backed.workflow;

import java.time.LocalDateTime;
import java.util.List;

/** 工作流任务。状态机：RUNNING→PENDING_CONFIRM→CONFIRMED/REJECTED；CONFIRMED→EXPORTED；异常→FAILED */
public record WorkflowTask(
        long id,
        String question,
        List<Long> kbIds,
        String status,
        String intent,
        String draft,
        String formatReport,   // Skill 输出的 JSON 报告
        String sources,        // 引用来源 JSON 数组
        String steps,          // 节点执行轨迹 JSON 数组（前端流程可视化用）
        String confirmComment,
        String errorMsg,
        long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final String RUNNING = "RUNNING";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPORTED = "EXPORTED";
    public static final String FAILED = "FAILED";
}