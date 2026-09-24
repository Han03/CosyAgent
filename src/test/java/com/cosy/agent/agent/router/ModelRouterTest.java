package com.cosy.agent.agent.router;

import com.cosy.agent.TestResilience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型路由引擎单测（模型路由 v2 验收）：
 * <ul>
 *   <li>候选 1 失败（连接失败/超时/5xx/429/熔断）→ 自动切候选 2</li>
 *   <li>指定模型（platform/model）→ 锁定单候选，不跨模型降级</li>
 *   <li>全部候选失败 → 聚合异常；4xx 客户端错误不降级</li>
 * </ul>
 */
class ModelRouterTest {

    private ChatModel modelA;
    private ChatModel modelB;
    private ChatModel defaultModel;
    private RouteConfig config;

    @BeforeEach
    void setUp() {
        modelA = mock(ChatModel.class);
        modelB = mock(ChatModel.class);
        defaultModel = mock(ChatModel.class);
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("openai", new RouteConfig.ModelPlatform("openai", "http://localhost:1", "sk-test"));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put(RouteConfig.DEFAULT_ROUTE, List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
        config = new RouteConfig(true, 5, platforms, routes);
    }

    private ModelRouter routerWith(boolean enabled) {
        RouteConfig cfg = new RouteConfig(enabled, 5, config.platforms(), config.routes());
        BiFunction<RouteConfig.ModelPlatform, String, ChatModel> factory =
                (pf, m) -> "gpt-4o".equals(m) ? modelB : modelA;
        ModelPlatformRegistry registry = new ModelPlatformRegistry(
                OpenAiChatOptions.builder().build(), factory);
        return new ModelRouter(defaultModel, OpenAiChatOptions.builder().build(),
                TestResilience.defaultResilience(), cfg, registry);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())));
    }

    @Test
    void firstCandidateFailsThenFallsBackToNextCandidate() {
        when(modelA.call(any(Prompt.class))).thenThrow(new ResourceAccessException("connection refused"));
        when(modelB.call(any(Prompt.class))).thenReturn(response("second candidate ok"));

        RouteResult result = routerWith(true).call(new Prompt("hi", OpenAiChatOptions.builder().build()), "auto");

        assertThat(result.chosenCandidate()).isEqualTo("openai/gpt-4o");
        assertThat(result.attempts()).containsExactly("openai/gpt-4o-mini", "openai/gpt-4o");
        assertThat(result.fallbackReasons()).hasSize(1);
        verify(modelA).call(any(Prompt.class));
        verify(modelB).call(any(Prompt.class));
    }

    @Test
    void explicitModelChoiceLocksSingleCandidateWithoutCrossModelFallback() {
        when(modelB.call(any(Prompt.class))).thenThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> routerWith(true)
                .call(new Prompt("hi", OpenAiChatOptions.builder().build()), "openai/gpt-4o"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("所有模型候选均调用失败");

        verify(modelA, never()).call(any(Prompt.class));
        verify(modelB).call(any(Prompt.class));
    }

    @Test
    void allCandidatesFailThrowsAggregateException() {
        when(modelA.call(any(Prompt.class))).thenThrow(new ResourceAccessException("conn A"));
        when(modelB.call(any(Prompt.class))).thenThrow(new ResourceAccessException("conn B"));

        assertThatThrownBy(() -> routerWith(true)
                .call(new Prompt("hi", OpenAiChatOptions.builder().build()), "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连接失败");
    }

    @Test
    void clientErrorDoesNotTriggerFallback() {
        when(modelA.call(any(Prompt.class)))
                .thenThrow(new HttpClientErrorException(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> routerWith(true)
                .call(new Prompt("hi", OpenAiChatOptions.builder().build()), "auto"))
                .isInstanceOf(HttpClientErrorException.class);

        verify(modelB, never()).call(any(Prompt.class));
    }

    @Test
    void disabledRoutingCallsDefaultModel() {
        when(defaultModel.call(any(Prompt.class))).thenReturn(response("default ok"));

        RouteResult result = routerWith(false)
                .call(new Prompt("hi", OpenAiChatOptions.builder().build()), "auto");

        assertThat(result.chosenCandidate()).isEqualTo("default");
        verify(defaultModel).call(any(Prompt.class));
        verify(modelA, never()).call(any(Prompt.class));
    }

    @Test
    void unknownPlatformChoiceThrowsImmediately() {
        assertThatThrownBy(() -> routerWith(true)
                .call(new Prompt("hi", OpenAiChatOptions.builder().build()), "unknown/model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("平台未注册");
    }
}
