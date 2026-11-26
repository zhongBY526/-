package com.intern.backed.common;

// 未登录或 token 失效，由 GlobalExceptionHandler 统一转 401
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}