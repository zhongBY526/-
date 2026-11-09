package com.intern.backed.config;

import dev.langchain4j.service.*;
import dev.langchain4j.service.spring.AiService;

import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

@AiService(wiringMode = EXPLICIT, streamingChatModel = "openAiStreamingChatModel")

public interface DeepSeekSteamAssaistent {
    @SystemMessage(fromResource ="messagePrompt.md" )
    TokenStream chat( @V("style")String style, @UserMessage String message);
}
