package com.intern.backed.chat;

import com.intern.backed.common.ApiResponse;
import com.intern.backed.dto.ExtractRequest;
import com.intern.backed.dto.ExtractResult;
import com.intern.backed.service.ExtractService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/extract")
public class ExtractController {

    private final ExtractService extractService;

    public ExtractController(ExtractService extractService) {
        this.extractService = extractService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExtractResult>> extract(
            @Valid @RequestBody ExtractRequest request) {

        String traceId = UUID.randomUUID().toString();

        try {
            ExtractResult result = extractService.extract(request.text(), traceId);
            return ResponseEntity.ok(new ApiResponse<>(0, "ok", result, traceId));
        } catch (Exception e) {
            log.error("extract failed, traceId={}", traceId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiResponse<>(502, "模型调用失败，请稍后重试", null, traceId));
        }
    }
}