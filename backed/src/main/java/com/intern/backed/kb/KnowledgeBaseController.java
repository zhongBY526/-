package com.intern.backed.kb;

import com.intern.backed.auth.UserContext;
import com.intern.backed.common.ApiResponse;
import com.intern.backed.dto.KbRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
// 知识库控制器类
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }
    // 列出当前用户可访问的知识库列表
    @GetMapping("/list")
    public ApiResponse<List<KnowledgeBase>> list() {
        return new ApiResponse<>(200, "ok", knowledgeBaseService.list(UserContext.currentUser()), null);
    }

    @PostMapping
    // 创建新知识库
    public ApiResponse<KnowledgeBase> create(@Valid @RequestBody KbRequest request) {
        return new ApiResponse<>(200, "ok", knowledgeBaseService.create(UserContext.currentUser(), request), null);
    }

    @PutMapping("/{kbId}")
    // 更新知识库
    public ApiResponse<KnowledgeBase> update(@PathVariable long kbId, @Valid @RequestBody KbRequest request) {
        return new ApiResponse<>(200, "ok", knowledgeBaseService.update(UserContext.currentUser(), kbId, request), null);
    }

    // 删除知识库
    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> delete(@PathVariable long kbId) {
        knowledgeBaseService.delete(UserContext.currentUser(), kbId);
        return new ApiResponse<>(200, "ok", null, null);
    }
}