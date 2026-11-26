package com.intern.backed.auth;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenStore {
    // 学习项目简化：token 存内存，重启后需重新登录；生产可换 Redis 或 JWT
    private final Map<String, User> tokens = new ConcurrentHashMap<>();

    public String issue(User user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, user);
        return token;
    }
    public User resolve(String token) {
        return token == null ? null : tokens.get(token);
    }
    public void remove(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }
}