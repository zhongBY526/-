package com.intern.backed.auth;

import com.intern.backed.common.UnauthorizedException;
// 请求级用户上下文，由 AuthInterceptor 写入并在 afterCompletion 清理
public final class UserContext {
    private static final ThreadLocal<User> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }
    public static void set(User user) {
        HOLDER.set(user);
    }

    public static User currentUser() {
        User user = HOLDER.get();
        if (user == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}