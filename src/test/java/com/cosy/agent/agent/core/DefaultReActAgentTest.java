package com.cosy.agent.agent.core;

import com.cosy.agent.TestResilience;
import com.cosy.agent.agent.memory.MemoryLevel;
import com.cosy.agent.agent.router.ModelRouter;
import com.cosy.agent.agent.router.RouteConfig;
import com.cosy.agent.agent.router.RouteResult;
import com.cosy.agent.agent.memory.MemoryRecord;
import com.cosy.agent.agent.memory.MemoryStore;
import com.cosy.agent.agent.mock.MockScriptEngine;
import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.tool.ServerTimeTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.agent.vector.VectorKnowledgeStore;
import com.cosy.agent.config.AgentProperties;
import com.cosy.agent.config.VectorProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环单元测试：以脚本化 ChatModel 桩驱动，验证
 * 工具调用 → Observation 回传 → 最终回答 / 终止条件，以及 Step 3 记忆注入与持久化、
 * Step 4 RAG 注入、Step 5 容错（重试自愈 / 熔断降级）。
 */
class DefaultReActAgentTest {

    private ChatModel chatModel;
    private ModelRouter modelRouter;
    private MemoryStore memoryStore;
    private VectorKnowledgeStore vectorStore;
    private DefaultReActAgent agent;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        modelRouter = mock(ModelRouter.class);
        memoryStore = mock(MemoryStore.class);
        vectorStore = mock(VectorKnowledgeStore.class);
        AgentProperties properties = new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT);
        VectorProperties vectorProperties = new VectorProperties("memory", "default", 5, 0.15, 600, 50, null);
        ToolRegistry registry = new ToolRegistry(List.of(new ServerTimeTool()));
        agent = new DefaultReActAgent(chatModel, OpenAiChatOptions.builder().build(), modelRouter, registry,
                memoryStore, vectorStore, TestResilience.defaultResilience(), properties, vectorProperties, null);
        when(memoryStore.list(any(), any())).thenReturn(List.of());
        when(memoryStore.load(any(), any(), any())).thenReturn(Optional.empty());
        when(vectorStore.search(any(), any(), anyInt(), anyDouble())).thenReturn(List.of());
    }

    /** 路由关闭的真实 ModelRouter：回退 defaultChatModel（容错测试验证 llm-retry/cb 落在模型调用上） */
    private ModelRouter disabledRouter(ResilienceSupport resilience) {
        return new ModelRouter(chatModel, OpenAiChatOptions.builder().build(), resilience,
                new RouteConfig(false, 5, Map.of(), Map.of()));
    }

    private RouteResult route(ChatResponse response) {
        return RouteResult.direct(response, "openai/test");
    }

    private DefaultReActAgent agentWith(ResilienceSupport resilience) {
        AgentProperties properties = new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT);
        VectorProperties vectorProperties = new VectorProperties("memory", "default", 5, 0.15, 600, 50, null);
        return new DefaultReActAgent(chatModel, OpenAiChatOptions.builder().build(), disabledRouter(resilience),
                new ToolRegistry(List.of(new ServerTimeTool())), memoryStore, vectorStore,
                resilience, properties, vectorProperties, null);
    }

    private AssistantMessage toolCallMessage(String content, String name, String arguments) {
        return AssistantMessage.builder()
                .content(content)
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", name, arguments)))
                .build();
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }

    @Test
    void completesAfterToolObservation() {
        when(modelRouter.call(any(Prompt.class), any()))
                .thenReturn(route(new ChatResponse(List.of(new Generation(toolCallMessage("我需要查询当前时间。", "get_server_time", "{}"))))),
                        route(response("当前时间已获取。")));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "现在几点？");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.answer()).isEqualTo("当前时间已获取。");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.trace()).extracting(AgentMessage::role)
                .containsExactly(AgentMessage.Role.USER, AgentMessage.Role.ASSISTANT, AgentMessage.Role.TOOL, AgentMessage.Role.ASSISTANT);
        assertThat(result.trace()).anyMatch(m -> m.role() == AgentMessage.Role.TOOL && m.toolName().equals("get_server_time"));
    }

    @Test
    void unknownToolIsReportedBackAsObservation() {
        when(modelRouter.call(any(Prompt.class), any()))
                .thenReturn(route(new ChatResponse(List.of(new Generation(toolCallMessage("调用不存在工具。", "not_exist", "{}"))))),
                        route(response("该工具不存在，我无法完成。")));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "调用不存在的工具");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.trace()).anyMatch(m -> m.role() == AgentMessage.Role.TOOL && m.content().contains("未知工具"));
    }

    @Test
    void stopsAtMaxIterationsWhenModelKeepsCallingTools() {
        when(modelRouter.call(any(Prompt.class), any()))
                .thenReturn(route(new ChatResponse(List.of(new Generation(toolCallMessage("继续查询。", "get_server_time", "{}"))))));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 3), "现在几点？");

        assertThat(result.state()).isEqualTo(AgentState.TIMEOUT);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.answer()).contains("最大迭代次数");
    }

    @Test
    void reportsFailureWhenLlmThrows() {
        when(modelRouter.call(any(Prompt.class), any())).thenThrow(new RuntimeException("connection refused"));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");

        assertThat(result.state()).isEqualTo(AgentState.FAILED);
        assertThat(result.errorMessage()).contains("connection refused");
        assertThat(result.answer()).isNull();
    }

    @Test
    void injectsSessionAndLongTermMemoryIntoSystemPrompt() {
        when(memoryStore.list(eq(MemoryLevel.SESSION), eq("s1")))
                .thenReturn(List.of(MemoryRecord.of(MemoryLevel.SESSION, "s1", "recent", "用户刚才问过天气", null)));
        when(memoryStore.list(eq(MemoryLevel.LONG_TERM), eq("u1")))
                .thenReturn(List.of(MemoryRecord.of(MemoryLevel.LONG_TERM, "u1", "fact:1", "用户偏好: 简洁回答", null)));
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("好的。")));

        agent.run(AgentContext.create("s1", "u1", 5), "继续");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelRouter, atLeastOnce()).call(captor.capture(), any());
        SystemMessage system = (SystemMessage) captor.getValue().getInstructions().get(0);
        assertThat(system.getText())
                .contains("【会话记忆】").contains("用户刚才问过天气")
                .contains("【用户长期记忆】").contains("用户偏好: 简洁回答");
    }

    @Test
    void persistsConversationAndWorkingStateAfterCompletion() {
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("已完成。")));

        agent.run(AgentContext.create("s1", "u1", 5), "执行任务");

        ArgumentCaptor<MemoryRecord> captor = ArgumentCaptor.forClass(MemoryRecord.class);
        verify(memoryStore, atLeastOnce()).save(captor.capture());
        List<MemoryRecord> saved = captor.getAllValues();
        assertThat(saved).anyMatch(r -> r.level() == MemoryLevel.SESSION && r.key().equals("recent")
                && r.value().contains("执行任务") && r.value().contains("已完成。"));
        assertThat(saved).anyMatch(r -> r.level() == MemoryLevel.WORKING && r.key().equals("state")
                && r.value().equals("COMPLETED"));
    }

    @Test
    void degradesGracefullyWhenMemoryUnavailable() {
        when(memoryStore.list(any(), any())).thenThrow(new RuntimeException("redis down"));
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("无记忆回答。")));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.answer()).isEqualTo("无记忆回答。");
    }

    @Test
    void injectsKnowledgeHitsIntoSystemPrompt() {
        when(vectorStore.search(eq("default"), eq("如何重置密码"), eq(5), eq(0.15)))
                .thenReturn(List.of(new VectorKnowledgeStore.KnowledgeHit("kb#0", "重置密码：进入设置页点击重置。", 0.91)));
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("好的。")));

        agent.run(AgentContext.create("s1", "u1", 5), "如何重置密码");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelRouter, atLeastOnce()).call(captor.capture(), any());
        SystemMessage system = (SystemMessage) captor.getValue().getInstructions().get(0);
        assertThat(system.getText())
                .contains("【知识库检索结果】").contains("重置密码：进入设置页点击重置。");
    }

    @Test
    void degradesGracefullyWhenKnowledgeSearchFails() {
        when(vectorStore.search(any(), any(), anyInt(), anyDouble())).thenThrow(new RuntimeException("pg down"));
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("直答。")));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.answer()).isEqualTo("直答。");
    }

    @Test
    void recoversFromTransientLlmFailuresViaRetry() {
        RetryRegistry retries = RetryRegistry.of(Map.of("llm-retry",
                RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ZERO).build()));
        agent = agentWith(new ResilienceSupport(retries, CircuitBreakerRegistry.ofDefaults(),
                io.github.resilience4j.ratelimiter.RateLimiterRegistry.ofDefaults(),
                io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(),
                io.github.resilience4j.bulkhead.BulkheadRegistry.ofDefaults()));

        AtomicInteger calls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("transient network error");
            }
            return response("重试后成功回答。");
        });

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.answer()).isEqualTo("重试后成功回答。");
        assertThat(calls.get()).isEqualTo(3); // 1 次原始 + 2 次重试
    }

    @Test
    void failsFastWithFriendlyMessageWhenCircuitBreakerOpen() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(Map.of("llm-cb",
                CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30)).build()));
        agent = agentWith(new ResilienceSupport(RetryRegistry.ofDefaults(), breakers,
                io.github.resilience4j.ratelimiter.RateLimiterRegistry.ofDefaults(),
                io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(),
                io.github.resilience4j.bulkhead.BulkheadRegistry.ofDefaults()));

        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("llm down"));

        // 连续失败触发熔断（重试耗尽仍失败 → 计入熔断窗口）
        for (int i = 0; i < 2; i++) {
            AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");
            assertThat(result.state()).isEqualTo(AgentState.FAILED);
        }

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");
        assertThat(result.state()).isEqualTo(AgentState.FAILED);
        assertThat(result.errorMessage()).contains("熔断");
    }

    @Test
    void injectsHistoryIntoModelContextOnResume() {
        // Step 6 断点恢复：历史 USER/ASSISTANT/TOOL 消息注入模型上下文（系统提示之后、本次输入之前）
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("恢复后继续回答。")));
        List<AgentMessage> history = List.of(
                AgentMessage.user("第一步问题"),
                AgentMessage.assistant("我需要调用工具"),
                AgentMessage.tool("call-1", "get_server_time", "{}", "{\"time\":\"2026-09-24 10:00:00\"}"));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "继续", history);

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.trace()).hasSize(history.size() + 2); // 历史 3 条 + 本次输入 + 最终回答
        assertThat(result.trace().get(0).content()).isEqualTo("第一步问题");
        assertThat(result.trace().get(3).content()).isEqualTo("继续");
        assertThat(result.trace().get(4).content()).isEqualTo("恢复后继续回答。");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelRouter).call(captor.capture(), any());
        List<org.springframework.ai.chat.messages.Message> messages = captor.getValue().getInstructions();
        assertThat(messages).hasSize(5); // system + 历史 3 条（USER/ASSISTANT/TOOL）+ 本次 USER
        assertThat(messages.get(1)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(3)).isInstanceOf(org.springframework.ai.chat.messages.ToolResponseMessage.class);
        assertThat(((org.springframework.ai.chat.messages.UserMessage) messages.get(4)).getText()).isEqualTo("继续");
    }

    @Test
    void exposesTaskIdWhenContextCarriesIt() {
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("完成。")));
        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5, "task-123"), "你好");
        assertThat(result.taskId()).isEqualTo("task-123");
    }

    @Test
    void usesMockEngineWhenRequestOverrideIsTrue() {
        MockScriptEngine engine = mock(MockScriptEngine.class);
        when(engine.generate(any(Prompt.class))).thenReturn(response("Mock 回答。"));
        agent = agentWithMockEngine(engine);
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("真实模型回答。")));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5, "task-1", Boolean.TRUE), "你好");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.answer()).isEqualTo("Mock 回答。");
        verify(engine).generate(any(Prompt.class));
        verify(modelRouter, org.mockito.Mockito.never()).call(any(Prompt.class), any());
    }

    @Test
    void fallsBackToRealModelWhenRequestOverrideIsFalse() {
        MockScriptEngine engine = mock(MockScriptEngine.class);
        when(engine.generate(any(Prompt.class))).thenReturn(response("Mock 回答。"));
        agent = agentWithMockEngine(engine);
        when(modelRouter.call(any(Prompt.class), any())).thenReturn(route(response("真实模型回答。")));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5, "task-1", Boolean.FALSE), "你好");

        assertThat(result.answer()).isEqualTo("真实模型回答。");
        verify(engine, org.mockito.Mockito.never()).generate(any(Prompt.class));
        verify(modelRouter).call(any(Prompt.class), any());
    }

    private DefaultReActAgent agentWithMockEngine(MockScriptEngine engine) {
        AgentProperties properties = new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT);
        VectorProperties vectorProperties = new VectorProperties("memory", "default", 5, 0.15, 600, 50, null);
        return new DefaultReActAgent(chatModel, OpenAiChatOptions.builder().build(), modelRouter,
                new ToolRegistry(List.of(new ServerTimeTool())), memoryStore, vectorStore,
                TestResilience.defaultResilience(), properties, vectorProperties, engine);
    }


    @Test
    void emitsReasoningAndToolResultDuration() {
        when(modelRouter.call(any(Prompt.class), any()))
                .thenReturn(route(new ChatResponse(List.of(new Generation(toolCallMessage("需要查询当前时间。", "get_server_time", "{}"))))),
                        route(response("当前时间已获取。")));

        List<AgentStreamEvent> events = new java.util.ArrayList<>();
        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "现在几点？", List.of(), events::add);

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        // 完整事件序列：thinking → reasoning → tool → toolResult → thinking → answer
        assertThat(events.stream().map(AgentStreamEvent::type).toList())
                .containsSubsequence("thinking", "reasoning", "tool", "toolResult", "thinking", "answer");
        // reasoning 携带该轮思考文本
        AgentStreamEvent reasoning = events.stream().filter(e -> e.type().equals("reasoning")).findFirst().orElseThrow();
        assertThat(reasoning.content()).isEqualTo("需要查询当前时间。");
        // toolResult 携带非负耗时
        AgentStreamEvent toolResult = events.stream().filter(e -> e.type().equals("toolResult")).findFirst().orElseThrow();
        assertThat(toolResult.durationMs()).isNotNull();
        assertThat(toolResult.durationMs()).isGreaterThanOrEqualTo(0L);
    }
}
