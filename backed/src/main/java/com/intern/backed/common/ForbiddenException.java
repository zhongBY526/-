package com.intern.backed.common;

// 已登录但无权访问目标资源，由 GlobalExceptionHandler 统一转 403
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}