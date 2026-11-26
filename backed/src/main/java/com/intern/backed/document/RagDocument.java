package com.intern.backed.document;

import java.time.LocalDateTime;

public record RagDocument(
        long id,
        long kbId,
        String fileName,
        String fileType,
        long fileSize,
        String status,
        String errorMsg,
        int chunkCount,
        long uploadedBy,
        LocalDateTime createdAt
) {
    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
}