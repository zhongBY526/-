package com.intern.backed.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intern.backed.auth.User;
import com.intern.backed.common.ForbiddenException;
import com.intern.backed.dto.WorkflowConfirmRequest;
import com.intern.backed.dto.WorkflowCreateRequest;
import com.intern.backed.kb.KbAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 工作流任务服务：状态机编排 + 人工确认。
 * RUNNING →(图执行成功)→ PENDING_CONFIRM →(人工)→ CONFIRMED / REJECTED
 * CONFIRMED →(导出)→ EXPORTED；REJECTED/FAILED →(重跑)→ RUNNING
 * 图执行异常 → FAILED（记录 errorMsg，可重跑，服务不中断）。
 */
@Slf4j
@Service
public class WorkflowService {
    private final WorkflowTaskRepository repository;
    private final DocWorkflow docWorkflow;
    private final KbAccessService accessService;
    private final Executor workflowExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowService(WorkflowTaskRepository repository,
                           DocWorkflow docWorkflow,
                           KbAccessService accessService,
                           @Qualifier("workflowExecutor") Executor workflowExecutor) {
        this.repository = repository;
        this.docWorkflow = docWorkflow;
        this.accessService = accessService;
        this.workflowExecutor = workflowExecutor;
    }

    public WorkflowTask create(User user, WorkflowCreateRequest request) {
        List<Long> kbIds = (request.kbIds() == null || request.kbIds().isEmpty())
                ? accessService.accessibleKbIds(user)
                : request.kbIds();
        // 权限校验必须留在请求线程（UserContext 是 ThreadLocal，异步线程拿不到）
        kbIds.forEach(id -> accessService.requireAccess(user, id));
        long id = repository.insert(request.question(), kbIds, user.id());
        workflowExecutor.execute(() -> execute(id, request.question(), kbIds));
        return get(user, id);
    }
    /** 异步执行图并落库；任何异常都收敛为 FAILED，不影响服务与其他任务 */
    private void execute(long taskId, String question, List<Long> kbIds) {
        try {
            DocWorkflowState result = docWorkflow.run(question, kbIds);
            boolean failed = !result.error().isBlank();
            repository.saveResult(taskId,
                    failed ? WorkflowTask.FAILED : WorkflowTask.PENDING_CONFIRM,
                    result.intent(), result.draft(),
                    toJson(result.formatReport()), toJson(result.sources()), toJson(result.steps()),
                    failed ? result.error() : null);
            log.info("workflow task {} 完成, intent={}, status={}", taskId, result.intent(),
                    failed ? "FAILED" : "PENDING_CONFIRM");
        } catch (Exception e) {
            log.error("workflow task {} 执行异常", taskId, e);
            repository.saveResult(taskId, WorkflowTask.FAILED, "", "",
                    "", "", "", "执行异常：" + e.getMessage());
        }
    }

    public List<WorkflowTask> list(User user) {
        return repository.listByUser(user.id(), user.isAdmin());
    }

    public WorkflowTask get(User user, long id) {
        WorkflowTask task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
        requireOwner(user, task);
        return task;
    }

    /** 人工确认环节：CONFIRM 通过 / REJECT 驳回（驳回后可重跑，实现"可回退"） */
    public WorkflowTask confirm(User user, long id, WorkflowConfirmRequest request) {
        WorkflowTask task = get(user, id);
        String target = "CONFIRM".equals(request.action()) ? WorkflowTask.CONFIRMED : WorkflowTask.REJECTED;
        boolean ok = repository.transition(id, WorkflowTask.PENDING_CONFIRM, target, request.comment());
        if (!ok) {
            throw new IllegalStateException("当前状态 " + task.status() + " 不允许确认操作（需 PENDING_CONFIRM）");
        }
        return get(user, id);
    }

    /** 驳回/失败任务重跑 */
    public WorkflowTask retry(User user, long id) {
        WorkflowTask task = get(user, id);
        if (!repository.resetForRetry(id)) {
            throw new IllegalStateException("当前状态 " + task.status() + " 不允许重跑（需 REJECTED/FAILED）");
        }
        workflowExecutor.execute(() -> execute(id, task.question(), task.kbIds()));
        return get(user, id);
    }

    /** 导出：CONFIRMED 首次导出转 EXPORTED；EXPORTED 允许重复下载 */
    public String export(User user, long id) {
        WorkflowTask task = get(user, id);
        if (!WorkflowTask.EXPORTED.equals(task.status())
                && !repository.transition(id, WorkflowTask.CONFIRMED, WorkflowTask.EXPORTED, task.confirmComment())) {
            throw new IllegalStateException("当前状态 " + task.status() + " 不允许导出（需先人工确认 CONFIRMED）");
        }
        StringBuilder md = new StringBuilder();
        md.append(task.draft()).append("\n\n---\n\n");
        md.append("> 任务 #").append(task.id()).append("　意图：").append(task.intent())
                .append("　人工确认备注：").append(task.confirmComment().isBlank() ? "无" : task.confirmComment())
                .append("\n");
        try {
            List<?> sources = objectMapper.readValue(task.sources(), List.class);
            if (!sources.isEmpty()) {
                md.append("> 引用来源：\n");
                for (Object o : sources) {
                    if (o instanceof java.util.Map<?, ?> s) {
                        md.append("> - ").append(s.get("file_name"))
                                .append(" · 段落 ").append(Integer.parseInt(String.valueOf(s.get("chunk_index"))) + 1)
                                .append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("导出引用来源解析失败: {}", e.getMessage());
        }
        return md.toString();
    }

    private void requireOwner(User user, WorkflowTask task) {
        if (task.createdBy() != user.id() && !user.isAdmin()) {
            throw new ForbiddenException("无权访问他人的工作流任务");
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}