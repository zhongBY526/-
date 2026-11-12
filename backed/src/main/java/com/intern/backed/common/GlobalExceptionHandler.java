package com.intern.backed.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 流式接口声明了 produces=text/event-stream，错误体要显式写 JSON
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("validation failed, traceId={}, message={}", traceId, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiResponse<>(400, message, null, traceId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.warn("malformed request body, traceId={}, error={}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiResponse<>(400, "请求体格式错误", null, traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        // 404/405/请求体格式错误等由 Spring 自带异常携带状态码，别一律改成 500
        if (e instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            log.warn("request failed, traceId={}, status={}, error={}",
                    traceId, status, e.getMessage());
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiResponse<>(status,
                            status == HttpStatus.NOT_FOUND.value() ? "资源不存在" : "请求无法处理",
                            null, traceId));
        }
        log.error("unhandled exception, traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiResponse<>(500, "服务内部错误，请稍后重试", null, traceId));
    }

    private String resolveTraceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }
}
