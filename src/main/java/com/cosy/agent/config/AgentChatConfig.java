package com.cosy.agent.config;

import com.cosy.agent.agent.tool.AgentToolBridging;
import com.cosy.agent.agent.tool.ToolRegistry;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * LLM 会话配置：构建携带工具定义（FunctionTool）的模型调用选项，供 ReAct 循环使用。
 */
@Configuration
public class AgentChatConfig {

    @Bean
    public OpenAiChatOptions agentChatOptions(
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.2}") Double temperature,
            ToolRegistry toolRegistry) {
        List<OpenAiApi.FunctionTool> tools = toolRegistry.all().stream()
                .map(AgentToolBridging::toFunctionTool)
                .toList();
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .tools(tools)
                .build();
    }
}
