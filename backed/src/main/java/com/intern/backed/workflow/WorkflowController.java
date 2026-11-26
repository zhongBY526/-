package com.intern.backed.workflow;

import com.intern.backed.auth.UserContext;
import com.intern.backed.common.ApiResponse;
import com.intern.backed.dto.WorkflowConfirmRequest;
import com.intern.backed.dto.WorkflowCreateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** 触发工作流：立即返回 RUNNING 任务，图在 workflowExecutor 异步执行 */
    @PostMapping("/tasks")
    public ApiResponse<WorkflowTask> create(@Valid @RequestBody WorkflowCreateRequest request) {
        return new ApiResponse<>(200, "ok", workflowService.create(UserContext.currentUser(), request), null);
    }

    @GetMapping("/tasks")
    public ApiResponse<List<WorkflowTask>> list() {
        return new ApiResponse<>(200, "ok", workflowService.list(UserContext.currentUser()), null);
    }

    /** 前端轮询任务状态（RUNNING→PENDING_CONFIRM/FAILED） */
    @GetMapping("/tasks/{id}")
    public ApiResponse<WorkflowTask> get(@PathVariable long id) {
        return new ApiResponse<>(200, "ok", workflowService.get(UserContext.currentUser(), id), null);
    }

    /** 人工确认：CONFIRM 通过 / REJECT 驳回 */
    @PostMapping("/tasks/{id}/confirm")
    public ApiResponse<WorkflowTask> confirm(@PathVariable long id,
                                             @Valid @RequestBody WorkflowConfirmRequest request) {
        return new ApiResponse<>(200, "ok", workflowService.confirm(UserContext.currentUser(), id, request), null);
    }

    /** 驳回/失败任务重跑（人工确认可回退） */
    @PostMapping("/tasks/{id}/retry")
    public ApiResponse<WorkflowTask> retry(@PathVariable long id) {
        return new ApiResponse<>(200, "ok", workflowService.retry(UserContext.currentUser(), id), null);
    }

    /** 导出 Markdown 文件（仅 CONFIRMED 可导出） */
    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long id) {
        String md = workflowService.export(UserContext.currentUser(), id);
        String filename = "workflow-task-" + id + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(md.getBytes(StandardCharsets.UTF_8));
    }
}