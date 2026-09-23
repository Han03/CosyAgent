package com.cosy.agent.config;

import com.cosy.agent.agent.mock.MockChatModelDecorator;
import com.cosy.agent.agent.mock.MockScriptEngine;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Mock 模块条件装配：{@code cosy.agent.mock.enabled=true} 时以装饰器覆盖
 * 自动配置的 ChatModel（{@code openAiChatModel}），其余注入点（ReAct 编排等）零改动。
 * 原模型按名注入并持有引用，支持开关透传与后续运行时切换。
 */
@Configuration
@ConditionalOnProperty(prefix = "cosy.agent.mock", name = "enabled", havingValue = "true")
public class MockChatConfig {

    @Bean
    @Primary
    public ChatModel mockChatModel(
            @Qualifier("openAiChatModel") ChatModel delegate,
            MockScriptEngine engine,
            AgentProperties properties) {
        return new MockChatModelDecorator(delegate, engine, properties.mock().enabled());
    }
}
