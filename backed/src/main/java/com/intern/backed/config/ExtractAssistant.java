package com.intern.backed.config;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

@AiService(wiringMode = EXPLICIT, chatModel = "openAiJsonChatModel")
public interface ExtractAssistant {
    @SystemMessage(fromResource = "extract-prompt.md")
    String chat(@UserMessage String message);
}
