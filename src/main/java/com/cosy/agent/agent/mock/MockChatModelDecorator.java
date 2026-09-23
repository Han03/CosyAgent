package com.cosy.agent.agent.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * LLM 端到端 Mock 装饰器：{@code cosy.agent.mock.enabled=true} 时以剧本引擎
 * 产出模型输出，替换真实 LLM 推理；开关关闭时透传真实模型（delegate）。
 * 仅替换「LLM 推理」一个依赖，其余全链路（编排/工具/记忆）真实执行。
 */
public class MockChatModelDecorator implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MockChatModelDecorator.class);

    private final ChatModel delegate;
    private final MockScriptEngine engine;
    private final boolean enabled;

    public MockChatModelDecorator(ChatModel delegate, MockScriptEngine engine, boolean enabled) {
        this.delegate = delegate;
        this.engine = engine;
        this.enabled = enabled;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (enabled) {
            log.debug("mock 模式：使用剧本引擎生成模型输出");
            return engine.generate(prompt);
        }
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (enabled) {
            return Flux.just(engine.generate(prompt));
        }
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate != null ? delegate.getDefaultOptions() : null;
    }
}
