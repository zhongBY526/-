package com.intern.backed.auth;

// 对外用户视图，不含密码哈希
public record UserView(
        long id,
        String username,
        String role,
        String department
) {}