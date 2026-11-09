package com.intern.backed.common;

public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId
) {}