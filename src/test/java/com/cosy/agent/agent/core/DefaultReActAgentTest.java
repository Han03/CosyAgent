package com.cosy.agent.agent.core;

import com.cosy.agent.agent.memory.MemoryLevel;
import com.cosy.agent.agent.memory.MemoryRecord;
import com.cosy.agent.agent.memory.MemoryStore;
import com.cosy.agent.agent.tool.ServerTimeTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.config.AgentProperties;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环单元测试：以脚本化 ChatModel 桩驱动，验证
 * 工具调用 → Observation 回传 → 最终回答 / 终止条件，以及 Step 3 记忆注入与持久化。
 */
class DefaultReActAgentTest {

    private ChatModel chatModel;
    private MemoryStore memoryStore;
    private DefaultReActAgent agent;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        memoryStore = mock(MemoryStore.class);
        AgentProperties properties = new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT);
        ToolRegistry registry = new ToolRegistry(List.of(new ServerTimeTool()));
        agent = new DefaultReActAgent(chatModel, OpenAiChatOptions.builder().build(), registry, memoryStore, properties);
        when(memoryStore.list(any(), any())).thenReturn(List.of());
        when(memoryStore.load(any(), any(), any())).thenReturn(Optional.empty());
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
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage("我需要查询当前时间。", "get_server_time", "{}")))),
                        response("当前时间已获取。"));

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
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage("调用不存在工具。", "not_exist", "{}")))),
                        response("该工具不存在，我无法完成。"));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "调用不存在的工具");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.trace()).anyMatch(m -> m.role() == AgentMessage.Role.TOOL && m.content().contains("未知工具"));
    }

    @Test
    void stopsAtMaxIterationsWhenModelKeepsCallingTools() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage("继续查询。", "get_server_time", "{}")))));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 3), "现在几点？");

        assertThat(result.state()).isEqualTo(AgentState.TIMEOUT);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.answer()).contains("最大迭代次数");
    }

    @Test
    void reportsFailureWhenLlmThrows() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));

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
        when(chatModel.call(any(Prompt.class))).thenReturn(response("好的。"));

        agent.run(AgentContext.create("s1", "u1", 5), "继续");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).call(captor.capture());
        SystemMessage system = (SystemMessage) captor.getValue().getInstructions().get(0);
        assertThat(system.getText())
                .contains("【会话记忆】").contains("用户刚才问过天气")
                .contains("【用户长期记忆】").contains("用户偏好: 简洁回答");
    }

    @Test
    void persistsConversationAndWorkingStateAfterCompletion() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("已完成。"));

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
        when(chatModel.call(any(Prompt.class))).thenReturn(response("无记忆回答。"));

        AgentResult result = agent.run(AgentContext.create("s1", "u1", 5), "你好");

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.answer()).isEqualTo("无记忆回答。");
    }
}
