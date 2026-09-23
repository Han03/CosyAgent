package com.cosy.agent.agent.core;

import com.cosy.agent.agent.tool.ServerTimeTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环单元测试：以脚本化 ChatModel 桩驱动，验证
 * 工具调用 → Observation 回传 → 最终回答 / 终止条件 的完整链路。
 */
class DefaultReActAgentTest {

    private ChatModel chatModel;
    private DefaultReActAgent agent;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        ToolRegistry registry = new ToolRegistry(List.of(new ServerTimeTool()));
        agent = new DefaultReActAgent(chatModel, OpenAiChatOptions.builder().build(), registry);
    }

    private AssistantMessage toolCallMessage(String content, String name, String arguments) {
        return AssistantMessage.builder()
                .content(content)
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", name, arguments)))
                .build();
    }

    @Test
    void completesAfterToolObservation() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage("我需要查询当前时间。", "get_server_time", "{}")))),
                        new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("当前时间已获取。").build()))));

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
                        new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("该工具不存在，我无法完成。").build()))));

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
}
