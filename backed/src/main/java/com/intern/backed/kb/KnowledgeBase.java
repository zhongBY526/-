package com.intern.backed.kb;

import java.time.LocalDateTime;
// 知识库的实体类
public record KnowledgeBase(
        long id,
        String name,
        String description,
        long ownerId,
        String department,
        String visibility,
        LocalDateTime createdAt
) {
    public static final String PRIVATE = "PRIVATE";
    public static final String DEPARTMENT = "DEPARTMENT";
    public static final String PUBLIC = "PUBLIC";
}