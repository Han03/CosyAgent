package com.cosy.agent.agent.router;

import com.cosy.agent.TestResilience;
import com.cosy.agent.agent.llmlog.CallRecorder;
import com.cosy.agent.agent.llmlog.LLMCallContext;
import com.cosy.agent.agent.llmlog.LlmCallLog;
import com.cosy.agent.agent.llmlog.LlmCallLogStore;
import com.cosy.agent.config.LlmCallLogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大模型调用记录验收：
 * <ul>
 *   <li>CallRecorder 采集 → flush 批量落库（明文内容/attempts/reasons/usage/status）</li>
 *   <li>失败路径：全部候选失败 → FAILED 记录（chosenModel=null + errorMsg）</li>
 *   <li>ModelRouter 埋点集成：候选降级时 beginCall/attemptFailed/attemptSucceeded/endCall 顺序</li>
 *   <li>内容策略：plain / truncated / none</li>
 * </ul>
 */
class LlmCallLogRecorderTest {

    private LlmCallLogStore store;
    private LlmCallLogProperties properties;

    @BeforeEach
    void setUp() {
        store = mock(LlmCallLogStore.class);
        properties = new LlmCallLogProperties(true, "plain", 2000, 1000, 50,
                new LlmCallLogProperties.Mysql("jdbc:mysql://localhost:3306/cosy", "cosy", "cosy"));
    }

    private CallRecorder recorder() {
        return new CallRecorder(store, properties);
    }

    @Test
    void successPath_recordsPlainContentAttemptsAndUsage() {
        CallRecorder recorder = recorder();
        LLMCallContext.set(new LLMCallContext.Context("s-1", "t-1", 3, null));
        String traceId = recorder.beginCall("auto", "default",
                List.of("p1/m1", "p1/m2"), "task-tag: reasoning", "用户问题");
        recorder.attemptFailed(traceId, "p1/m1", "连接失败: boom");
        recorder.attemptSucceeded(traceId, "p1/m2", "最终答案", 1200, 10, 20, 30);
        recorder.endCall(traceId, Instant.now(), true, null);
        LLMCallContext.clear();

        recorder.flush();
        verify(store).saveAll(anyList());
        org.mockito.ArgumentCaptor<List<LlmCallLog>> cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).saveAll(cap.capture());
        LlmCallLog entry = cap.getValue().get(0);
        assertThat(entry.status()).isEqualTo("SUCCESS");
        assertThat(entry.chosenModel()).isEqualTo("p1/m2");
        assertThat(entry.sessionId()).isEqualTo("s-1");
        assertThat(entry.taskId()).isEqualTo("t-1");
        assertThat(entry.iteration()).isEqualTo(3);
        assertThat(entry.candidateChain()).isEqualTo("p1/m1,p1/m2");
        assertThat(entry.attempts()).isEqualTo("p1/m1");
        assertThat(entry.reasons()).contains("连接失败");
        assertThat(entry.promptContent()).isEqualTo("用户问题");   // 明文
        assertThat(entry.responseContent()).isEqualTo("最终答案");
        assertThat(entry.promptTokens()).isEqualTo(10);
        assertThat(entry.completionTokens()).isEqualTo(20);
        assertThat(entry.totalTokens()).isEqualTo(30);
        assertThat(entry.decisionRationale()).isEqualTo("task-tag: reasoning");
        assertThat(entry.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void allCandidatesFailed_recordsFailedWithoutChosenModel() {
        CallRecorder recorder = recorder();
        String traceId = recorder.beginCall("auto", "default",
                List.of("p1/m1", "p1/m2"), null, "q");
        recorder.attemptFailed(traceId, "p1/m1", "连接失败: x");
        recorder.attemptFailed(traceId, "p1/m2", "HTTP 503: y");
        recorder.endCall(traceId, Instant.now(), false, "所有模型候选均调用失败");

        recorder.flush();
        org.mockito.ArgumentCaptor<List<LlmCallLog>> cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).saveAll(cap.capture());
        LlmCallLog entry = cap.getValue().get(0);
        assertThat(entry.status()).isEqualTo("FAILED");
        assertThat(entry.chosenModel()).isNull();
        assertThat(entry.attempts()).isEqualTo("p1/m1,p1/m2");
        assertThat(entry.reasons()).contains("HTTP 503");
        assertThat(entry.errorMsg()).contains("所有模型候选均调用失败");
    }

    @Test
    void contentModes_plainTruncatedNone() {
        assertThat(properties.applyContent("你好世界")).isEqualTo("你好世界");
        LlmCallLogProperties truncated = new LlmCallLogProperties(true, "truncated", 3, 100, 50,
                properties.mysql());
        assertThat(truncated.applyContent("你好世界")).isEqualTo("你好世…");
        LlmCallLogProperties none = new LlmCallLogProperties(true, "none", 3, 100, 50, properties.mysql());
        assertThat(none.applyContent("你好世界")).isNull();
    }

    @Test
    void modelRouter_instrumentation_onDegradation() {
        ChatModel modelA = mock(ChatModel.class);
        ChatModel modelB = mock(ChatModel.class);
        ChatModel defaultModel = mock(ChatModel.class);
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("p1", new RouteConfig.ModelPlatform("p1", "http://localhost:1", "sk-test", "/v1/chat/completions"));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put(RouteConfig.DEFAULT_ROUTE, List.of("p1/m1", "p1/m2"));
        RouteConfig cfg = new RouteConfig(true, 5, platforms, routes);
        when(modelA.call(any(Prompt.class)))
                .thenThrow(new ResourceAccessException("connect failed"));
        when(modelB.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("ok").build()))));
        ModelPlatformRegistry registry = new ModelPlatformRegistry(
                OpenAiChatOptions.builder().build(),
                (pf, m) -> "m2".equals(m) ? modelB : modelA);

        CallRecorder recorder = recorder();
        ModelRouter router = new ModelRouter(defaultModel, OpenAiChatOptions.builder().build(),
                TestResilience.defaultResilience(), cfg, registry);
        router.setRecorder(recorder);

        var result = router.call(new Prompt("hello"), "auto");
        assertThat(result.chosenCandidate()).isEqualTo("p1/m2");
        assertThat(result.fallbackReasons().get(0)).contains("连接失败");

        recorder.flush();
        org.mockito.ArgumentCaptor<List<LlmCallLog>> cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).saveAll(cap.capture());
        LlmCallLog entry = cap.getValue().get(0);
        assertThat(entry.status()).isEqualTo("SUCCESS");
        assertThat(entry.chosenModel()).isEqualTo("p1/m2");
        assertThat(entry.attempts()).isEqualTo("p1/m1");
        assertThat(entry.reasons()).contains("连接失败");
        assertThat(entry.responseContent()).isEqualTo("ok");
    }

    @Test
    void modelRouter_instrumentation_allFailed_recordsFailed() {
        ChatModel modelA = mock(ChatModel.class);
        ChatModel modelB = mock(ChatModel.class);
        ChatModel defaultModel = mock(ChatModel.class);
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("p1", new RouteConfig.ModelPlatform("p1", "http://localhost:1", "sk-test", "/v1/chat/completions"));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put(RouteConfig.DEFAULT_ROUTE, List.of("p1/m1", "p1/m2"));
        RouteConfig cfg = new RouteConfig(true, 5, platforms, routes);
        when(modelA.call(any(Prompt.class))).thenThrow(new ResourceAccessException("connect failed"));
        when(modelB.call(any(Prompt.class))).thenThrow(new ResourceAccessException("connect failed too"));
        ModelPlatformRegistry registry = new ModelPlatformRegistry(
                OpenAiChatOptions.builder().build(),
                (pf, m) -> "m2".equals(m) ? modelB : modelA);

        CallRecorder recorder = recorder();
        ModelRouter router = new ModelRouter(defaultModel, OpenAiChatOptions.builder().build(),
                TestResilience.defaultResilience(), cfg, registry);
        router.setRecorder(recorder);

        assertThatThrownBy(() -> router.call(new Prompt("hello"), "auto"))
                .isInstanceOf(IllegalStateException.class);

        recorder.flush();
        org.mockito.ArgumentCaptor<List<LlmCallLog>> cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).saveAll(cap.capture());
        LlmCallLog entry = cap.getValue().get(0);
        assertThat(entry.status()).isEqualTo("FAILED");
        assertThat(entry.chosenModel()).isNull();
        assertThat(entry.attempts()).isEqualTo("p1/m1,p1/m2");
        assertThat(entry.errorMsg()).contains("所有模型候选均调用失败");
    }
}
