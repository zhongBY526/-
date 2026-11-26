package com.intern.backed.controller;

import com.intern.backed.auth.User;
import com.intern.backed.auth.UserContext;
import com.intern.backed.common.ApiResponse;
import com.intern.backed.dto.RagChatRequest;
import com.intern.backed.dto.RagResult;
import com.intern.backed.graph.RagWorkflow;
import com.intern.backed.kb.KbAccessService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
@RestController
@RequestMapping("/api/rag")
public class RagChatController {
    private final RagWorkflow ragWorkflow;
    private final KbAccessService accessService;

    public RagChatController(RagWorkflow ragWorkflow, KbAccessService accessService) {
        this.ragWorkflow = ragWorkflow;
        this.accessService = accessService;
    }

    @PostMapping("/chat")
    public ApiResponse<RagResult> chat(@Valid @RequestBody RagChatRequest request) {
        User user = UserContext.currentUser();
        List<Long> kbIds = (request.kbIds() == null || request.kbIds().isEmpty())
                ? accessService.accessibleKbIds(user)
                : request.kbIds();
        // 用户显式指定的 kbId 也要逐个校验权限，防越权检索
        kbIds.forEach(id -> accessService.requireAccess(user, id));
        return new ApiResponse<>(200, "ok", ragWorkflow.chat(request.question(), kbIds), null);
    }
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public SseEmitter chatStream(@Valid @RequestBody RagChatRequest request, HttpServletResponse response) {
        User user = UserContext.currentUser();
        List<Long> kbIds = (request.kbIds() == null || request.kbIds().isEmpty())
                ? accessService.accessibleKbIds(user)
                : request.kbIds();
        kbIds.forEach(id -> accessService.requireAccess(user, id)); // 权限校验必须留在请求线程！
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(360_000L);
        ragWorkflow.chatStream(request.question(), kbIds, emitter);
        return emitter;
    }
}
