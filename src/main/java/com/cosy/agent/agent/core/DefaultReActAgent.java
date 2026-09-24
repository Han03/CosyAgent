package com.cosy.agent.agent.core;

import com.cosy.agent.agent.memory.MemoryLevel;
import com.cosy.agent.agent.memory.MemoryRecord;
import com.cosy.agent.agent.memory.MemoryStore;
import com.cosy.agent.agent.mock.MockScriptEngine;
import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.agent.tool.AgentTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.agent.vector.VectorKnowledgeStore;
import com.cosy.agent.config.AgentProperties;
import com.cosy.agent.config.VectorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
import java.util.Optional;

/**
 * 默认 ReAct 智能体：Thought → Action → Observation 循环。
 *
 * <p>循环由本类显式驱动（而非 ChatClient 自动执行），以获得完整可控性：
 * 调用 LLM 获得思考与工具调用意图 → 经 ToolRegistry 执行工具 → 将工具结果作为
 * Observation 回传模型 → 重复，直至模型给出最终回答或触发终止条件。
 * 终止条件：最终回答 / 达到 max-iterations / LLM 调用异常。</p>
 *
 * <p>Step 3 记忆集成：运行前注入会话记忆与长期记忆到系统提示；
 * 运行后持久化最近对话（滚动窗口）与工作状态。记忆读写失败自动降级为无记忆直答。</p>
 *
 * <p>Step 4 RAG 集成：运行前以用户输入检索知识库（TopK + 阈值），命中注入系统提示；
 * 检索失败自动降级（跳过 RAG，不阻断推理）。</p>
 *
 * <p>Step 5 容错集成：LLM 推理与工具调用统一施加 Resilience4j 组合策略
 * （重试/熔断/限流/超时/舱壁）；熔断开启时返回友好降级文案，不雪崩。</p>
 */
@Service
public class DefaultReActAgent implements ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultReActAgent.class);

    /** 滚动会话记录保留条数（约 3 轮对话） */
    private static final int RECENT_LIMIT = 6;

    private static final String RECENT_KEY = "recent";

    private static final String SYSTEM_PROMPT = """
            你是 CosyAgent，一个企业级任务型智能体。请遵循 ReAct 模式完成任务：
            1. Thought：分析用户意图，规划下一步行动；
            2. Action：如需外部信息或操作，调用系统提供的工具；
            3. Observation：根据工具返回结果继续推理；
            4. 当信息足够时，直接输出最终回答（使用与用户相同的语言）。
            仅可调用系统明确提供的工具，不要编造工具名。""";

    private final ChatModel chatModel;
    private final OpenAiChatOptions chatOptions;
    private final com.cosy.agent.agent.router.ModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final VectorKnowledgeStore vectorStore;
    private final ResilienceSupport resilience;
    private final AgentProperties properties;
    private final VectorProperties vectorProperties;
    private final MockScriptEngine mockEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultReActAgent(ChatModel chatModel, OpenAiChatOptions chatOptions,
                             com.cosy.agent.agent.router.ModelRouter modelRouter,
                             ToolRegistry toolRegistry, MemoryStore memoryStore,
                             VectorKnowledgeStore vectorStore, ResilienceSupport resilience,
                             AgentProperties properties, VectorProperties vectorProperties,
                             MockScriptEngine mockEngine) {
        this.chatModel = chatModel;
        this.chatOptions = chatOptions;
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.memoryStore = memoryStore;
        this.vectorStore = vectorStore;
        this.resilience = resilience;
        this.properties = properties;
        this.vectorProperties = vectorProperties;
        this.mockEngine = mockEngine;
    }

    @Override
    public String name() {
        return "default-react-agent";
    }

    @Override
    public AgentResult run(AgentContext context, String userInput) {
        return run(context, userInput, List.of());
    }

    @Override
    public AgentResult run(AgentContext context, String userInput, List<AgentMessage> history) {
        return run(context, userInput, history, null);
    }

    @Override
    public AgentResult run(AgentContext context, String userInput, List<AgentMessage> history,
                           AgentEventListener listener) {
        long start = System.currentTimeMillis();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(context, userInput)));
        List<AgentMessage> trace = new ArrayList<>();
        if (history != null) {
            for (AgentMessage historic : history) {
                if (historic.role() == AgentMessage.Role.SYSTEM) {
                    continue; // 系统提示由当前轮重新构建，历史系统消息不注入
                }
                messages.add(toSpringMessage(historic));
                trace.add(historic);
            }
        }
        messages.add(new UserMessage(userInput));
        trace.add(AgentMessage.user(userInput));

        AgentState state = AgentState.RUNNING;
        String answer = null;
        String errorMessage = null;
        int iterations = 0;

        for (int i = 0; i < context.maxIterations(); i++) {
            iterations++;
            emit(listener, AgentStreamEvent.thinking(iterations));
            boolean useMock = isMockActive(context);
            ChatResponse response;
            try {
                if (useMock) {
                    // Mock 开启：短路到剧本引擎（不进入模型路由）
                    response = resilience.execute(ResilienceTarget.LLM,
                            () -> mockEngine.generate(new Prompt(messages, chatOptions)));
                } else {
                    // 真实模型：走模型路由（候选链 + 降级；每个候选内部已套 llm 容错）
                    response = modelRouter.call(new Prompt(messages, chatOptions), context.modelChoice()).response();
                }
            } catch (Exception e) {
                log.error("LLM 调用失败，sessionId={}, iteration={}", context.sessionId(), iterations, e);
                state = AgentState.FAILED;
                errorMessage = hasCause(e, CallNotPermittedException.class)
                        ? "模型服务暂时不可用（熔断中），请稍后重试"
                        : "模型调用失败: " + rootMessage(e);
                break;
            }

            AssistantMessage assistant = response.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();

            if (toolCalls == null || toolCalls.isEmpty()) {
                answer = assistant.getText();
                emit(listener, AgentStreamEvent.answer(answer));
                trace.add(AgentMessage.assistant(answer));
                state = AgentState.COMPLETED;
                break;
            }

            // 行动前的思考：该轮模型在工具调用前的推理文本透出（无文本则不发）
            String thought = assistant.getText();
            if (thought != null && !thought.isBlank()) {
                emit(listener, AgentStreamEvent.reasoning(iterations, thought));
            }
            trace.add(AgentMessage.assistant(thought == null ? "" : thought));
            messages.add(assistant);

            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                emit(listener, AgentStreamEvent.tool(toolCall.id(), toolCall.name(), toolCall.arguments(), iterations));
                long startNs = System.nanoTime();
                AgentTool tool = toolRegistry.find(toolCall.name()).orElse(null);
                Object result;
                if (tool == null) {
                    result = Map.of("error", "未知工具: " + toolCall.name());
                } else {
                    try {
                        Map<String, Object> args = parseArgs(toolCall.arguments());
                        result = resilience.execute(ResilienceTarget.TOOL,
                                () -> tool.execute(args), tool.retryable());
                    } catch (Exception e) {
                        log.warn("工具执行失败: name={}", toolCall.name(), e);
                        result = Map.of("error", "工具执行失败: " + rootMessage(e));
                    }
                }
                String resultJson = toJson(result);
                long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                emit(listener, AgentStreamEvent.toolResult(toolCall.id(), toolCall.name(), resultJson, durationMs));
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

        persistMemory(context, userInput, answer, state);

        Object taskIdAttr = context.attributes().get("taskId");
        String taskId = taskIdAttr != null ? taskIdAttr.toString() : null;
        return new AgentResult(context.sessionId(), answer, state, List.copyOf(trace), iterations,
                System.currentTimeMillis() - start, errorMessage, taskId);
    }

    /** 事件回调：监听器为空或发送失败时静默跳过（不改变执行控制流，流断不阻断任务） */
    private void emit(AgentEventListener listener, AgentStreamEvent event) {
        if (listener == null) {
            return;
        }
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            log.debug("流事件发送失败（客户端可能已断开）: type={}", event.type());
        }
    }

    /** 组装系统提示：基础 ReAct 指令 + 会话记忆 + 长期记忆 + 知识库检索结果（RAG）。
     * 记忆/知识读取失败时降级（不阻断推理）。 */
    private String buildSystemPrompt(AgentContext context, String userInput) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        appendMemoryBlock(sb, "会话记忆", MemoryLevel.SESSION, context.sessionId());
        appendMemoryBlock(sb, "用户长期记忆", MemoryLevel.LONG_TERM, context.userId());
        appendKnowledgeBlock(sb, context, userInput);
        return sb.toString();
    }

    /** 请求级 Mock 判定：X-Cosy-Mock 请求头注入的覆盖值优先，缺失时回退全局配置；
     * Mock 引擎未装配（无 MockScriptEngine bean）时恒走真实模型。 */
    private boolean isMockActive(AgentContext context) {
        if (mockEngine == null) {
            return false;
        }
        Boolean override = context.mockOverride();
        return override != null ? override : properties.mock().enabled();
    }

    private void appendMemoryBlock(StringBuilder sb, String label, MemoryLevel level, String namespace) {
        try {
            List<MemoryRecord> records = memoryStore.list(level, namespace);
            if (!records.isEmpty()) {
                sb.append("\n\n【").append(label).append("】\n");
                records.forEach(r -> sb.append("- ").append(r.key()).append(": ").append(r.value()).append('\n'));
            }
        } catch (Exception e) {
            log.warn("记忆读取失败，降级为无记忆: level={}, namespace={}", level, namespace, e);
        }
    }

    /** 以用户输入检索知识库（RAG），命中注入系统提示；失败降级跳过。 */
    private void appendKnowledgeBlock(StringBuilder sb, AgentContext context, String userInput) {
        try {
            List<VectorKnowledgeStore.KnowledgeHit> hits = vectorStore.search(
                    vectorProperties.namespace(), userInput, vectorProperties.topK(), vectorProperties.minScore());
            if (!hits.isEmpty()) {
                sb.append("\n\n【知识库检索结果】\n");
                hits.forEach(hit -> sb.append("- [").append(hit.docId()).append("] ")
                        .append(hit.content()).append('\n'));
                log.info("知识库命中 {} 条注入系统提示: sessionId={}, namespace={}",
                        hits.size(), context.sessionId(), vectorProperties.namespace());
            }
        } catch (Exception e) {
            log.warn("知识检索失败，降级跳过 RAG: sessionId={}", context.sessionId(), e);
        }
    }

    /** 运行后持久化：滚动会话记录 + 工作状态。写入失败仅告警，不影响返回结果。 */
    private void persistMemory(AgentContext context, String userInput, String answer, AgentState state) {
        try {
            persistConversation(context.sessionId(), userInput, answer);
            memoryStore.save(MemoryRecord.of(MemoryLevel.WORKING, context.sessionId(),
                    "state", state.name(), properties.workingTimeout()));
        } catch (Exception e) {
            log.warn("记忆写入失败，忽略: sessionId={}", context.sessionId(), e);
        }
    }

    private void persistConversation(String sessionId, String userInput, String answer) {
        List<Map<String, String>> recent = loadRecent(sessionId);
        recent.add(Map.of("role", "user", "content", userInput));
        if (answer != null) {
            recent.add(Map.of("role", "assistant", "content", answer));
        }
        int from = Math.max(0, recent.size() - RECENT_LIMIT);
        recent = new ArrayList<>(recent.subList(from, recent.size()));
        memoryStore.save(MemoryRecord.of(MemoryLevel.SESSION, sessionId, RECENT_KEY,
                toJson(recent), properties.sessionTimeout()));
    }

    private List<Map<String, String>> loadRecent(String sessionId) {
        Optional<String> json = memoryStore.load(MemoryLevel.SESSION, sessionId, RECENT_KEY);
        if (json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json.get(), new TypeReference<List<Map<String, String>>>() {
            }));
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
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

    /** 历史轨迹消息 → Spring AI Message（Step 6 断点恢复：USER/ASSISTANT/TOOL 三类） */
    private Message toSpringMessage(AgentMessage historic) {
        return switch (historic.role()) {
            case USER -> new UserMessage(historic.content());
            case ASSISTANT -> new AssistantMessage(historic.content() == null ? "" : historic.content());
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            historic.toolCallId() == null ? "tool-" + historic.toolName() : historic.toolCallId(),
                            historic.toolName(),
                            historic.content() == null ? "" : historic.content())))
                    .build();
            case SYSTEM -> new SystemMessage(historic.content());
        };
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

    private boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        Throwable current = e;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
