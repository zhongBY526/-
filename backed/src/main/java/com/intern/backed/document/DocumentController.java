package com.intern.backed.document;

import com.intern.backed.auth.UserContext;
import com.intern.backed.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
public class DocumentController {
    private final DocumentService documentService;
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }
    @PostMapping("/{kbId}/documents")
    public ApiResponse<RagDocument> upload(@PathVariable long kbId,@RequestParam("file") MultipartFile file){
        return new ApiResponse<>(200, "ok",
                documentService.upload(UserContext.currentUser(), kbId, file), null);
    }
    @GetMapping("/{kbId}/documents")
    public ApiResponse<List<RagDocument>> list(@PathVariable long kbId) {
        return new ApiResponse<>(200, "ok",
                documentService.list(UserContext.currentUser(), kbId), null);
    }

    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Void> delete(@PathVariable long docId) {
        documentService.delete(UserContext.currentUser(), docId);
        return new ApiResponse<>(200, "ok", null, null);
    }
}
