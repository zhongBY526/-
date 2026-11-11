package com.intern.backed.chat;

import com.intern.backed.config.DeepSeekSteamAssaistent;
import com.intern.backed.dto.ChatRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
                // 参数校验由 @Valid 完成，失败直接 400，不建立 SSE
                response.setHeader("X-Accel-Buffering", "no");
                response.setHeader("Cache-Control", "no-cache");

                String messageId = "m-" + UUID.randomUUID();
                String traceId = UUID.randomUUID().toString();
                String userId = request.userId() == null || request.userId().isBlank()
                        ? "guest"
                        : request.userId().trim();
                String conversationId = request.conversationId() == null
                        || request.conversationId().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.conversationId().trim();
                String memoryId = userId + ":" + conversationId;
                log.info("chat start, traceId={}, memoryId={}, style={}, messageLength={}",
                        traceId, memoryId, request.style(), request.message().length());
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
                            .data(Map.of(
                                    "messageId", messageId,
                                    "conversationId", conversationId), MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    finished.set(true);
                    log.info("SSE meta send stopped: {}", e.toString());
                    return emitter;
                }
                // 3. 开始调用模型
                deepSeekSteamAssaistent.chat(memoryId, request.style(), request.message())
                        .onPartialResponse(delta -> {
                            if (finished.get()) {
                                return;
                            }
                            if (firstTokenMs.compareAndSet(-1,
                                    (System.nanoTime() - startedAt) / 1_000_000)) {
                                log.info("chat first token, traceId={}, firstTokenMs={}",
                                        traceId, firstTokenMs.get());
                            }
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
                            String finishReason = resp.finishReason() == null
                                    ? "stop"
                                    : resp.finishReason().name().toLowerCase();
                            HashMap<String, Object> done = new HashMap<>();
                            done.put("finishReason", finishReason);
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
                            log.info("chat done, traceId={}, finishReason={}, promptTokens={}, completionTokens={}, firstTokenMs={}",
                                    traceId, finishReason,
                                    usage == null ? -1 : usage.inputTokenCount(),
                                    usage == null ? -1 : usage.outputTokenCount(),
                                    firstTokenMs.get());
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
