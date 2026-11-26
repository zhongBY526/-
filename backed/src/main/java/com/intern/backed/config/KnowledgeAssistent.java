package com.intern.backed.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "openAiChatModel")
public interface KnowledgeAssistent {
    @SystemMessage("""
        你是一个知识库问答助手。

        请根据检索到的知识库资料回答用户问题。

        要求：
        1. 优先依据知识库资料回答。
        2. 不要编造知识库中不存在的信息。
        3. 如果检索资料不足，请明确告诉用户。
        4. 使用中文回答。
        """)
    String chat(@UserMessage String question);

}
