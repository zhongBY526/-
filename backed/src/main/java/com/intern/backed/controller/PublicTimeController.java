package com.intern.backed.controller;

import com.intern.backed.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 公开时间端点：供 HttpTimeTool 演示真实 HTTP 工具调用，刻意不进 AuthInterceptor */
@RestController
@RequestMapping("/api/public")
public class PublicTimeController {
    @GetMapping("/now")
    public ApiResponse<Map<String, String>> now() {
        return new ApiResponse<>(200, "ok", Map.of(
                "now", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")),
                "source", "server"), null);
    }
}