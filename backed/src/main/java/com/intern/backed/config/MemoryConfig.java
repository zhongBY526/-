package com.intern.backed.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class MemoryConfig {
    //提供chatmemroywindows
    @Bean
    public ChatMemoryProvider chatMemoryProvider(){
        return memoryId->MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .build();
    }
}
