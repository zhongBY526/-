package com.intern.backed.chat;

import com.intern.backed.config.DeepSeekSteamAssaistent;
import com.intern.backed.dto.ChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class DeepseekStreamChat {
    private final DeepSeekSteamAssaistent deepSeekSteamAssaistent;
    public DeepseekStreamChat(DeepSeekSteamAssaistent deepSeekSteamAssaistent) {
        this.deepSeekSteamAssaistent = deepSeekSteamAssaistent;
    }
    static final Set<String> ALLOWED_STYLES = Set.of("严谨","温和","简洁");
    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
                // 1. 先校验，非法参数直接 400，不建立 SSE
                Set<String> allowedStyles = Set.of("严谨", "温和", "简洁");
                if (!allowedStyles.contains(request.style())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "style 仅支持 严谨/温和/简洁");
                }
                response.setHeader("X-Accel-Buffering", "no");
                response.setHeader("Cache-Control", "no-cache");

                String messageId = "m-" + UUID.randomUUID();
                String traceId = UUID.randomUUID().toString();
                long startedAt = System.nanoTime();
                AtomicLong firstTokenMs = new AtomicLong(-1);
                AtomicBoolean finished = new AtomicBoolean(false);

                SseEmitter emitter = new SseEmitter(60_000L);

                emitter.onCompletion(() -> {
                    finished.set(true);
                    long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
                    log.info("SSE completed, traceId={}, totalMs={}", traceId, totalMs);
                });

                emitter.onTimeout(() -> {
                    finished.set(true);
                    log.warn("SSE timeout, traceId={}", traceId);
                });

                emitter.onError(e -> {
                    finished.set(true);
                    log.warn("SSE client disconnected, traceId={}, error={}", traceId, e.toString());
                });

                // 2. 发送 meta
                try {
                    emitter.send(SseEmitter.event()
                            .name("meta")
                            .data(Map.of("messageId", messageId), MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    finished.set(true);
                    log.info("SSE meta send stopped: {}", e.toString());
                    return emitter;
                }
                // 3. 开始调用模型
                deepSeekSteamAssaistent.chat(request.style(), request.message())
                        .onPartialResponse(delta -> {
                            if (finished.get()) {
                                return;
                            }
                            firstTokenMs.compareAndSet(-1,
                                    (System.nanoTime() - startedAt) / 1_000_000);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(Map.of("delta", delta), MediaType.APPLICATION_JSON));
                            } catch (IOException | IllegalStateException e) {
                                finished.set(true);
                                log.info("SSE token send stopped: {}", e.toString());
                            }
                        })
                        .onCompleteResponse(resp -> {
                            if (finished.get()) {
                                return;
                            }
                            HashMap<String, Object> done = new HashMap<>();
                            done.put("finishReason", resp.finishReason() == null
                                    ? "stop"
                                    : resp.finishReason().name().toLowerCase());
                            done.put("firstTokenMs",
                                    firstTokenMs.get() == -1 ? 0 : firstTokenMs.get());
                            var usage = resp.tokenUsage();
                            if (usage == null) {
                                done.put("usage", null);
                            } else {
                                done.put("usage", Map.of(
                                        "promptTokens", usage.inputTokenCount(),
                                        "completionTokens", usage.outputTokenCount()));
                            }
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(done, MediaType.APPLICATION_JSON));
                            } catch (IOException | IllegalStateException e) {
                                log.info("SSE done send stopped: {}", e.toString());
                            }
                            if (finished.compareAndSet(false, true)) {
                                emitter.complete();
                            }
                        })
                        .onError(err -> {
                            log.error("LLM stream failed, traceId={}", traceId, err);

                            if (finished.get()) {
                                return;
                            }

                            String message = err.getMessage() == null
                                    ? ""
                                    : err.getMessage().toLowerCase();

                            String code;
                            if (message.contains("timeout")) {
                                code = "MODEL_TIMEOUT";
                            } else if (message.contains("429") || message.contains("rate limit")) {
                                code = "MODEL_RATE_LIMITED";
                            } else {
                                code = "MODEL_ERROR";
                            }

                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(Map.of(
                                                "code", code,
                                                "message", "模型调用失败，请稍后重试",
                                                "traceId", traceId), MediaType.APPLICATION_JSON));
                            } catch (IOException | IllegalStateException e) {
                                log.info("SSE error send stopped: {}", e.toString());
                            }

                            if (finished.compareAndSet(false, true)) {
                                emitter.complete();
                            }
                        })
                        .start();

                return emitter;
            }
        }
