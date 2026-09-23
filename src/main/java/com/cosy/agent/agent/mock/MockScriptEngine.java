package com.cosy.agent.agent.mock;

import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 剧本引擎：按剧本主干推进回合，注入随机性，产出模型输出（ChatResponse）。
 *
 * <p>无共享可变状态：每轮输出由 Prompt 历史推导（已发出的工具调用数 = 推进位置），
 * 支持并发请求；脚本选择由首条用户消息决定（关键词匹配或按哈希稳定随机），
 * 同一运行内多次调用选择一致。</p>
 *
 * <p>随机性：scripted 模式固定种子可复现（CI）；random 模式以用户消息哈希为锚，
 * 每次运行独立随机。随机注入：附加轮（多考虑一轮）、未知工具（自愈路径）、
 * 多工具并行、模型异常（FAILED 路径）。</p>
 */
@Component
public class MockScriptEngine {

    private static final Logger log = LoggerFactory.getLogger(MockScriptEngine.class);

    private final ToolRegistry toolRegistry;
    private final MockScriptLibrary library;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong callIdSeq = new AtomicLong();

    public MockScriptEngine(ToolRegistry toolRegistry, MockScriptLibrary library, AgentProperties properties) {
        this.toolRegistry = toolRegistry;
        this.library = library;
        this.properties = properties;
    }

    public ChatResponse generate(Prompt prompt) {
        AgentProperties.Mock mock = properties.mock();
        AgentProperties.Mock.Probability prob = mock.probability() != null ? mock.probability()
                : AgentProperties.Mock.DEFAULT.probability();
        MockRandomSource random = randomSource(prompt, mock);
        MockScript script = selectScript(prompt, mock);
        int progress = countToolCallMessages(prompt);

        if (script.loopForever()) {
            return toolCallResponse(List.of(pickAction(script, random)));
        }
        if (script.injectable() && random.chance(prob.error())) {
            throw new IllegalStateException("模拟模型异常（mock error 注入）");
        }
        if (script.injectable() && random.chance(prob.multiTool())) {
            return toolCallResponse(List.of(pickAction(script, random), pickAction(script, random)));
        }
        if (progress < script.turns().size()) {
            MockTurn turn = script.turns().get(progress);
            if (turn.type() == MockTurnType.FINAL_ANSWER
                    && script.injectable() && random.chance(prob.extraTurn())
                    && progress < mock.maxTurns() - 1) {
                return toolCallResponse(List.of(pickAction(script, random)));
            }
            return switch (turn.type()) {
                case TOOL_CALL -> toolCallResponse(List.of(maybeUnknownTool(random, prob, random.pick(turn.actions()))));
                case FINAL_ANSWER -> finalAnswerResponse(turn.answerVariants(), prompt, random);
                case RAISE_ERROR -> throw new IllegalStateException("模拟模型异常（剧本 RAISE_ERROR 回合）");
            };
        }
        // 主干已耗尽：随机追加一轮工具调用（模拟模型多考虑一轮），否则给出最终回答
        if (script.injectable() && random.chance(prob.extraTurn()) && progress < mock.maxTurns() - 1) {
            return toolCallResponse(List.of(pickAction(script, random)));
        }
        return finalAnswerResponse(List.of("任务已完成：{result}", "完成：{result}", "结果：{result}"), prompt, random);
    }

    private MockRandomSource randomSource(Prompt prompt, AgentProperties.Mock mock) {
        long userHash = firstUserText(prompt).hashCode();
        if ("scripted".equalsIgnoreCase(mock.mode())) {
            return new MockRandomSource(mock.seed());
        }
        // random 模式：以用户消息哈希 + 运行时刻为锚，每次运行独立随机
        return new MockRandomSource(userHash ^ System.nanoTime());
    }

    private MockScript selectScript(Prompt prompt, AgentProperties.Mock mock) {
        String userText = firstUserText(prompt);
        if ("scripted".equalsIgnoreCase(mock.mode())) {
            return library.byId(mock.script()).orElseGet(() -> library.byId("time").orElseThrow());
        }
        return library.byId(keywordScript(userText)).orElseGet(() -> weightedRandom(userText));
    }

    private String keywordScript(String userText) {
        if (containsAny(userText, "时间", "clock")) {
            return "time";
        }
        if (containsAny(userText, "综合", "巡检", "多轮")) {
            return "combined";
        }
        if (containsAny(userText, "服务器", "信息")) {
            return "server-info";
        }
        if (containsAny(userText, "自愈", "不存在", "未知工具")) {
            return "self-heal";
        }
        if (containsAny(userText, "循环", "超限", "一直")) {
            return "loop-limit";
        }
        return "";
    }

    /** 按用户消息哈希稳定加权随机挑选（random 模式；loop-limit 权重 0 不参与） */
    private MockScript weightedRandom(String userText) {
        List<MockScript> pool = library.all().stream()
                .filter(s -> !s.loopForever())
                .toList();
        int[] weights = {3, 3, 2, 1};
        long target = Math.floorMod(firstUserTextHash(userText), totalWeight(weights, pool.size()));
        int acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += weights[i % weights.length];
            if (target < acc) {
                return pool.get(i);
            }
        }
        return pool.get(0);
    }

    private long totalWeight(int[] weights, int poolSize) {
        long total = 0;
        for (int i = 0; i < poolSize; i++) {
            total += weights[i % weights.length];
        }
        return total;
    }

    private long firstUserTextHash(String userText) {
        return userText == null ? 0 : userText.hashCode();
    }

    private MockAction pickAction(MockScript script, MockRandomSource random) {
        List<MockAction> actions = new ArrayList<>();
        for (MockTurn turn : script.turns()) {
            if (turn.type() == MockTurnType.TOOL_CALL) {
                actions.addAll(turn.actions());
            }
        }
        if (actions.isEmpty()) {
            List<String> names = new ArrayList<>(toolRegistry.names());
            return MockAction.of(names.get(random.nextInt(names.size())));
        }
        return random.pick(actions);
    }

    private MockAction maybeUnknownTool(MockRandomSource random, AgentProperties.Mock.Probability prob, MockAction action) {
        if (random.chance(prob.unknownTool())) {
            return MockAction.of("mock_unknown_tool", action.argumentTemplate());
        }
        return action;
    }

    private ChatResponse toolCallResponse(List<MockAction> actions) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (MockAction action : actions) {
            calls.add(new AssistantMessage.ToolCall(
                    "mock_call_" + callIdSeq.incrementAndGet(), "function",
                    action.toolName(), toJson(action.argumentTemplate())));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("我需要调用工具继续处理。")
                .toolCalls(calls)
                .build())));
    }

    private ChatResponse finalAnswerResponse(List<String> variants, Prompt prompt, MockRandomSource random) {
        String variant = random.pick(variants);
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(variant.replace("{result}", lastObservation(prompt)))
                .build())));
    }

    /** 提取最近一次工具观察结果（供最终回答引用真实执行结果） */
    private String lastObservation(Prompt prompt) {
        for (int i = prompt.getInstructions().size() - 1; i >= 0; i--) {
            Message message = prompt.getInstructions().get(i);
            if (message instanceof ToolResponseMessage toolResponse
                    && toolResponse.getResponses() != null && !toolResponse.getResponses().isEmpty()) {
                return toolResponse.getResponses().get(0).responseData();
            }
        }
        return "已获取";
    }

    private int countToolCallMessages(Prompt prompt) {
        int count = 0;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String firstUserText(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            if (message instanceof UserMessage user) {
                String text = user.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }
}
