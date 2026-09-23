package com.cosy.agent.agent.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装饰器路由测试：开关开启走引擎，关闭透传真实模型。
 */
class MockChatModelDecoratorTest {

    private final ChatModel delegate = mock(ChatModel.class);
    private final MockScriptEngine engine = mock(MockScriptEngine.class);
    private final Prompt prompt = new Prompt("你好");

    private ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    @Test
    void enabledRoutesToEngine() {
        MockChatModelDecorator decorator = new MockChatModelDecorator(delegate, engine, true);
        ChatResponse expected = answer("模拟回答");
        when(engine.generate(prompt)).thenReturn(expected);

        assertThat(decorator.call(prompt)).isEqualTo(expected);
        verify(engine).generate(prompt);
        verify(delegate, never()).call(prompt);
    }

    @Test
    void disabledForwardsToDelegate() {
        MockChatModelDecorator decorator = new MockChatModelDecorator(delegate, engine, false);
        ChatResponse expected = answer("真实回答");
        when(delegate.call(prompt)).thenReturn(expected);

        assertThat(decorator.call(prompt)).isEqualTo(expected);
        verify(delegate).call(prompt);
        verify(engine, never()).generate(prompt);
    }
}
