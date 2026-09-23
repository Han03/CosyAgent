package com.cosy.agent.agent.core;

import com.cosy.agent.agent.tool.AgentTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认 ReAct 智能体：Thought → Action → Observation 循环。
 *
 * <p>循环由本类显式驱动（而非 ChatClient 自动执行），以获得完整可控性：
 * 调用 LLM 获得思考与工具调用意图 → 经 ToolRegistry 执行工具 → 将工具结果作为
 * Observation 回传模型 → 重复，直至模型给出最终回答或触发终止条件。
 * 终止条件：最终回答 / 达到 max-iterations / LLM 调用异常。</p>
 */
@Service
public class DefaultReActAgent implements ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultReActAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是 CosyAgent，一个企业级任务型智能体。请遵循 ReAct 模式完成任务：
            1. Thought：分析用户意图，规划下一步行动；
            2. Action：如需外部信息或操作，调用系统提供的工具；
            3. Observation：根据工具返回结果继续推理；
            4. 当信息足够时，直接输出最终回答（使用与用户相同的语言）。
            仅可调用系统明确提供的工具，不要编造工具名。""";

    private final ChatModel chatModel;
    private final OpenAiChatOptions chatOptions;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultReActAgent(ChatModel chatModel, OpenAiChatOptions chatOptions, ToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.chatOptions = chatOptions;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "default-react-agent";
    }

    @Override
    public AgentResult run(AgentContext context, String userInput) {
        long start = System.currentTimeMillis();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(userInput));

        List<AgentMessage> trace = new ArrayList<>();
        trace.add(AgentMessage.user(userInput));

        AgentState state = AgentState.RUNNING;
        String answer = null;
        String errorMessage = null;
        int iterations = 0;

        for (int i = 0; i < context.maxIterations(); i++) {
            iterations++;
            ChatResponse response;
            try {
                response = chatModel.call(new Prompt(messages, chatOptions));
            } catch (Exception e) {
                log.error("LLM 调用失败，sessionId={}, iteration={}", context.sessionId(), iterations, e);
                state = AgentState.FAILED;
                errorMessage = "模型调用失败: " + rootMessage(e);
                break;
            }

            AssistantMessage assistant = response.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();

            if (toolCalls == null || toolCalls.isEmpty()) {
                answer = assistant.getText();
                trace.add(AgentMessage.assistant(answer));
                state = AgentState.COMPLETED;
                break;
            }

            trace.add(AgentMessage.assistant(assistant.getText() == null ? "" : assistant.getText()));
            messages.add(assistant);

            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                AgentTool tool = toolRegistry.find(toolCall.name()).orElse(null);
                Object result;
                if (tool == null) {
                    result = Map.of("error", "未知工具: " + toolCall.name());
                } else {
                    try {
                        result = tool.execute(parseArgs(toolCall.arguments()));
                    } catch (Exception e) {
                        log.warn("工具执行失败: name={}", toolCall.name(), e);
                        result = Map.of("error", "工具执行失败: " + rootMessage(e));
                    }
                }
                String resultJson = toJson(result);
                trace.add(AgentMessage.tool(toolCall.id(), toolCall.name(), toolCall.arguments(), resultJson));
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), resultJson)))
                        .build());
            }
        }

        if (state == AgentState.RUNNING) {
            state = AgentState.TIMEOUT;
            answer = "已达到最大迭代次数（" + context.maxIterations() + " 轮），任务未能完成。";
        }

        return new AgentResult(context.sessionId(), answer, state, List.copyOf(trace), iterations,
                System.currentTimeMillis() - start, errorMessage);
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String msg = current.getMessage();
        return msg == null || msg.isBlank() ? current.getClass().getSimpleName() : msg;
    }
}
