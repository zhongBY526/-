package com.intern.backed.auth;

// 内部用户模型，含密码哈希；对外输出必须转 UserView，禁止直接序列化本类
public record User(
        long id,
        String username,
        String passwordHash,
        String role,
        String department
) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public UserView toView() {
        return new UserView(id, username, role, department);
    }
}