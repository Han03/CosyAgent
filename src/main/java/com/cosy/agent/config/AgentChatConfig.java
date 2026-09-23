package com.cosy.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 会话客户端配置：基于 Spring AI 自动装配的 ChatClient.Builder 构建。
 * Step 2 起用于 ReAct 推理循环（Thought / Action / Observation）。
 */
@Configuration
public class AgentChatConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
