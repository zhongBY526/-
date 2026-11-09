package com.intern.backed.config;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

@AiService(
        wiringMode = EXPLICIT,
        chatModel = "openAiChatModel"
)
public interface DeepSeekAssistent {
    String chat(@MemoryId Object memoryId, @UserMessage String message);
}
