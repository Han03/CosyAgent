package com.cosy.agent.agent.mock;

import com.cosy.agent.agent.tool.ServerInfoTool;
import com.cosy.agent.agent.tool.ServerTimeTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 剧本引擎单元测试：剧本推进、随机注入、种子可复现、各终止路径。
 */
class MockScriptEngineTest {

    private final ToolRegistry registry = new ToolRegistry(List.of(new ServerTimeTool(), new ServerInfoTool()));

    private AgentProperties props(AgentProperties.Mock mock) {
        return new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), mock);
    }

    private AgentProperties.Mock mock(String mode, String script, AgentProperties.Mock.Probability prob) {
        return new AgentProperties.Mock(true, mode, script, 42, 8, prob, AgentProperties.Mock.Latency.DISABLED);
    }

    private AgentProperties.Mock.Probability prob(double extra, double unknown, double multi, double error) {
        return new AgentProperties.Mock.Probability(extra, unknown, multi, error);
    }

    private MockScriptEngine engine(AgentProperties properties) {
        return new MockScriptEngine(registry, new MockScriptLibrary(), properties);
    }

    private Prompt prompt(String userText, List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是 CosyAgent。"));
        messages.add(new UserMessage(userText));
        messages.addAll(history);
        return new Prompt(messages, OpenAiChatOptions.builder().build());
    }

    private Message assistantWithToolCall(String id, String name) {
        return AssistantMessage.builder()
                .content("调用工具。")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                .build();
    }

    private Message toolResponse(String callId, String result) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "get_server_time", result)))
                .build();
    }

    private AssistantMessage output(ChatResponse response) {
        return response.getResult().getOutput();
    }

    @Test
    void scriptedTimeRunsToolThenFinalAnswer() {
        MockScriptEngine engine = engine(props(mock("scripted", "time", prob(0, 0, 0, 0))));

        ChatResponse first = engine.generate(prompt("现在几点？", List.of()));
        assertThat(output(first).getToolCalls()).hasSize(1);
        assertThat(output(first).getToolCalls().get(0).name()).isEqualTo("get_server_time");

        List<Message> history = List.of(assistantWithToolCall("call_1", "get_server_time"),
                toolResponse("call_1", "{\"time\":\"2026-09-24 10:00:00\"}"));
        ChatResponse second = engine.generate(prompt("现在几点？", history));
        assertThat(output(second).getToolCalls()).isNullOrEmpty();
        assertThat(output(second).getText()).contains("2026-09-24 10:00:00");
    }

    @Test
    void scriptedSeedIsReproducible() {
        MockScriptEngine engineA = engine(props(mock("scripted", "time", prob(1.0, 1.0, 0, 0))));
        MockScriptEngine engineB = engine(props(mock("scripted", "time", prob(1.0, 1.0, 0, 0))));

        ChatResponse a = engineA.generate(prompt("现在几点？", List.of()));
        ChatResponse b = engineB.generate(prompt("现在几点？", List.of()));
        assertThat(output(a).getToolCalls()).hasSameSizeAs(output(b).getToolCalls());
        assertThat(output(a).getToolCalls().get(0).name()).isEqualTo(output(b).getToolCalls().get(0).name());
        assertThat(output(a).getToolCalls().get(0).id()).isEqualTo(output(b).getToolCalls().get(0).id());
    }

    @Test
    void unknownToolInjectionUsesUnregisteredName() {
        MockScriptEngine engine = engine(props(mock("scripted", "time", prob(0, 1.0, 0, 0))));

        ChatResponse response = engine.generate(prompt("现在几点？", List.of()));
        String name = output(response).getToolCalls().get(0).name();
        assertThat(registry.find(name)).isEmpty();
    }

    @Test
    void multiToolInjectionEmitsParallelCalls() {
        MockScriptEngine engine = engine(props(mock("scripted", "time", prob(0, 0, 1.0, 0))));

        ChatResponse response = engine.generate(prompt("现在几点？", List.of()));
        assertThat(output(response).getToolCalls()).hasSize(2);
    }

    @Test
    void errorInjectionThrowsToTriggerFailedPath() {
        MockScriptEngine engine = engine(props(mock("scripted", "time", prob(0, 0, 0, 1.0))));

        assertThatThrownBy(() -> engine.generate(prompt("现在几点？", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟模型异常");
    }

    @Test
    void extraTurnInjectsOneMoreToolCallBeforeFinal() {
        MockScriptEngine engine = engine(props(mock("scripted", "time", prob(1.0, 0, 0, 0))));

        List<Message> history = List.of(assistantWithToolCall("call_1", "get_server_time"),
                toolResponse("call_1", "{\"time\":\"10:00\"}"));
        ChatResponse second = engine.generate(prompt("现在几点？", history));
        assertThat(output(second).getToolCalls()).hasSize(1);

        // 附加轮持续注入直到达到 max-turns-1 后才输出最终回答
        List<Message> history7 = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            history7.add(assistantWithToolCall("call_" + i, "get_server_time"));
            history7.add(toolResponse("call_" + i, "{\"time\":\"10:00\"}"));
        }
        ChatResponse finalTurn = engine.generate(prompt("现在几点？", history7));
        assertThat(output(finalTurn).getToolCalls()).isNullOrEmpty();
        assertThat(output(finalTurn).getText()).isNotBlank();
    }

    @Test
    void selfHealScriptCorrectsUnknownTool() {
        MockScriptEngine engine = engine(props(mock("scripted", "self-heal", prob(0, 0, 0, 0))));

        ChatResponse first = engine.generate(prompt("自愈演示", List.of()));
        assertThat(output(first).getToolCalls().get(0).name()).isEqualTo("mock_unknown_tool");

        List<Message> history = List.of(assistantWithToolCall("call_1", "mock_unknown_tool"),
                toolResponse("call_1", "{\"error\":\"未知工具\"}"));
        ChatResponse second = engine.generate(prompt("自愈演示", history));
        assertThat(output(second).getToolCalls().get(0).name()).isEqualTo("get_server_time");
    }

    @Test
    void loopLimitScriptKeepsCallingTools() {
        MockScriptEngine engine = engine(props(mock("scripted", "loop-limit", prob(0, 0, 0, 0))));

        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ChatResponse response = engine.generate(prompt("循环直到超限", history));
            assertThat(output(response).getToolCalls()).hasSize(1);
            history.add(assistantWithToolCall("call_" + i, "get_server_time"));
            history.add(toolResponse("call_" + i, "{\"time\":\"10:00\"}"));
        }
    }
}
