package com.cosy.agent.agent.router;

import com.cosy.agent.agent.llmlog.CallRecorder;
import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 模型路由引擎（模型路由 v2）：解析调用选择（auto / 指定模型）→ 候选链 →
 * 逐个调用（每个候选套 llm 容错：重试/熔断/限流/超时）→ 可降级异常时切换下一候选。
 *
 * <p>降级语义：连接失败、超时、5xx、429、熔断打开 → 切下一候选；4xx 等客户端错误
 * 不降级（切模型无意义，直接抛出）。全部候选失败抛聚合异常。</p>
 *
 * <p>配置热更新：{@link #refresh(RouteConfig)} 更新运行时配置并清空平台缓存，
 * 管理 API PUT 后即时生效。</p>
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatModel defaultChatModel;
    private final OpenAiChatOptions templateOptions;
    private final ResilienceSupport resilience;
    private final ModelPlatformRegistry registry;
    private final com.cosy.agent.agent.tool.ToolRegistry toolRegistry; // 可空：null 时工具取 templateOptions 快照
    private final AtomicReference<RouteConfig> config;
    private AutoRouter autoRouter; // 可空：auto 决策层（v2.1）；null 时 auto 走静态链（现状）
    private CallRecorder recorder; // 可空：调用记录器（旁路采集，不装配时零影响）
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ModelRouter(ChatModel defaultChatModel, OpenAiChatOptions templateOptions,
                       ResilienceSupport resilience, RouteConfig baseline) {
        this(defaultChatModel, templateOptions, resilience, baseline,
                new ModelPlatformRegistry(templateOptions), null);
    }

    /** 测试/定制用：注入平台注册表（工具动态注入关闭，工具取 templateOptions 快照） */
    ModelRouter(ChatModel defaultChatModel, OpenAiChatOptions templateOptions,
                ResilienceSupport resilience, RouteConfig baseline, ModelPlatformRegistry registry) {
        this(defaultChatModel, templateOptions, resilience, baseline, registry, null);
    }

    /** 定制用：注入平台注册表与工具注册表（每轮调用动态取工具定义，能力注册即时生效） */
    public ModelRouter(ChatModel defaultChatModel, OpenAiChatOptions templateOptions,
                       ResilienceSupport resilience, RouteConfig baseline, ModelPlatformRegistry registry,
                       com.cosy.agent.agent.tool.ToolRegistry toolRegistry) {
        this.defaultChatModel = defaultChatModel;
        this.templateOptions = templateOptions;
        this.resilience = resilience;
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.config = new AtomicReference<>(baseline);
    }

    /** 当前运行时配置（管理 API 读取用） */
    public RouteConfig currentConfig() {
        return config.get();
    }

    /** 装配自动路由决策层（v2.1；未装配时 auto 走静态链，行为与 v2.0 一致） */
    public void setAutoRouter(AutoRouter autoRouter) {
        this.autoRouter = autoRouter;
    }

    /** 装配调用记录器（旁路；未装配时模型调用不产生记录） */
    public void setRecorder(CallRecorder recorder) {
        this.recorder = recorder;
    }

    /** 平台注册表（管理 API 连通性测试用） */
    public ModelPlatformRegistry registry() {
        return registry;
    }

    /** 配置热更新：替换运行时配置并清空平台实例缓存 */
    public void refresh(RouteConfig updated) {
        config.set(updated);
        registry.invalidate();
        log.info("模型路由配置已热更新: enabled={}, routes={}", updated.enabled(), updated.routes().keySet());
    }

    /**
     * 执行一次模型调用。
     *
     * @param prompt     消息序列 + 模板 options（含工具定义/温度）；候选 options 以模板复制并替换 model
     * @param modelChoice auto（缺省同义）| "平台/模型"（指定即锁定单候选）
     */
    public RouteResult call(Prompt prompt, String modelChoice) {
        return call(prompt, modelChoice, null);
    }

    /**
     * 执行一次模型调用（路由类型感知）。
     *
     * @param prompt     消息序列 + 模板 options（含工具定义/温度）；候选 options 以模板复制并替换 model
     * @param modelChoice auto（缺省同义）| "平台/模型"（指定即锁定单候选，忽略 routeType）
     * @param routeType  路由类型（如 reasoning/default）；auto 模式按类型取链，缺失回退 default
     */
    public RouteResult call(Prompt prompt, String modelChoice, String routeType) {
        RouteConfig cfg = config.get();
        // 兼容回退：路由关闭时走默认单模型（原行为，含 llm 容错）
        if (!cfg.enabled()) {
            ChatResponse resp = resilience.execute(ResilienceTarget.LLM, () -> defaultChatModel.call(prompt));
            return RouteResult.direct(resp, "default");
        }

        List<String> candidates;
        String rationale = null;
        boolean auto = modelChoice == null || modelChoice.isBlank()
                || "auto".equalsIgnoreCase(modelChoice.trim());
        boolean explicitType = routeType != null && !routeType.isBlank();
        if (auto && !explicitType && autoRouter != null) {
            // v2.1 自动路由决策层：auto 且未显式指定类型 → 决策器选链/排序
            RouteContext ctx = new RouteContext(modelChoice, routeType,
                    prompt.getInstructions(), dynamicTools(), 0);
            RouteDecision decision = autoRouter.decide(ctx);
            candidates = decision.candidates();
            rationale = decision.rationale();
            if (candidates.isEmpty()) {
                candidates = cfg.resolveCandidates(modelChoice, routeType); // 决策空链回退静态
            }
        } else {
            candidates = cfg.resolveCandidates(modelChoice, routeType);
        }
        if (candidates.isEmpty()) {
            // 路由链为空：退化为默认单模型（如平台未配置）
            log.warn("模型路由候选链为空（routes 未配置），回退默认模型: choice={}", modelChoice);
            ChatResponse resp = resilience.execute(ResilienceTarget.LLM, () -> defaultChatModel.call(prompt));
            return RouteResult.direct(resp, "default");
        }

        // ---- 调用记录埋点（旁路）：begin → 候选 attempt → end ----
        String traceId = recorder == null ? null
                : recorder.beginCall(modelChoice, routeType, candidates, rationale,
                        promptText(prompt), rawPromptJson(prompt));
        List<String> attempts = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        RuntimeException lastFailure = null;
        try {
            for (String candidate : candidates) {
                attempts.add(candidate);
                long attemptStart = System.currentTimeMillis();
                try {
                    ChatResponse resp = callCandidate(cfg, candidate, prompt);
                    recordSuccess(traceId, candidate, resp, System.currentTimeMillis() - attemptStart);
                    if (!reasons.isEmpty()) {
                        log.info("模型路由降级命中: choice={}, attempts={}, reasons={}, chosen={}",
                                modelChoice, attempts, reasons, candidate);
                    }
                    return RouteResult.ok(resp, candidate, attempts, reasons);
                } catch (RuntimeException e) {
                    if (!isFallbackable(e)) {
                        throw e; // 不可降级异常（如 4xx/参数错误）：立即终止，不切换候选
                    }
                    String reason = classify(e);
                    reasons.add(reason);
                    if (traceId != null) {
                        recorder.attemptFailed(traceId, candidate, reason);
                    }
                    lastFailure = e;
                    log.warn("模型路由候选失败，切换下一候选: candidate={}, reason={}", candidate, reason);
                }
            }
            log.error("模型路由全部候选失败: choice={}, candidates={}, reasons={}",
                    modelChoice, candidates, reasons);
            throw new IllegalStateException("所有模型候选均调用失败: " + reasons, lastFailure);
        } catch (RuntimeException e) {
            if (traceId != null) {
                recorder.endCall(traceId, Instant.now(), false, e.getMessage());
            }
            throw e;
        }
    }

    // ---- 调用记录埋点辅助（异常安全：记录失败绝不影响调用链） ----

    private void recordSuccess(String traceId, String candidate, ChatResponse resp, long attemptMs) {
        if (traceId == null) {
            return;
        }
        try {
            recorder.attemptSucceeded(traceId, candidate, responseText(resp), attemptMs,
                    usage(resp, 0), usage(resp, 1), usage(resp, 2));
            recorder.endCall(traceId, Instant.now(), true, null);
        } catch (RuntimeException e) {
            log.warn("LLM 调用记录成功埋点失败: {}", e.getMessage());
        }
    }

    private static String promptText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(m -> {
                    var t = m.getText();
                    return t == null ? "" : t;
                })
                .collect(Collectors.joining("\n"));
    }

    /** 发给 LLM 的原始提示词（结构化 JSON）：逐条消息 role+content + 工具定义 + 模型名。
     *  序列化失败返回 null（记录侧该字段留空，不影响调用链）。 */
    private String rawPromptJson(Prompt prompt) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (Message m : prompt.getInstructions()) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", roleOf(m));
                String text = m.getText();
                msg.put("content", text == null ? "" : text);
                msgs.add(msg);
            }
            req.put("messages", msgs);
            if (templateOptions != null) {
                if (templateOptions.getModel() != null) {
                    req.put("model", templateOptions.getModel());
                }
                var tools = templateOptions.getTools();
                if (tools != null && !tools.isEmpty()) {
                    req.put("tools", tools);
                }
            }
            return objectMapper.writeValueAsString(req);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("原始提示词序列化失败（该字段留空）: {}", e.getMessage());
            return null;
        }
    }

    private static String roleOf(Message m) {
        if (m instanceof SystemMessage) {
            return "system";
        }
        if (m instanceof UserMessage) {
            return "user";
        }
        if (m instanceof AssistantMessage) {
            return "assistant";
        }
        return m.getMessageType() == null ? "message" : m.getMessageType().name().toLowerCase();
    }

    private static String responseText(ChatResponse resp) {
        try {
            var out = resp.getResult().getOutput();
            return out == null ? null : out.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** usage 提取（0=prompt / 1=completion / 2=total；不可用时 null） */
    private static Integer usage(ChatResponse resp, int idx) {
        try {
            var usage = resp.getMetadata().getUsage();
            if (usage == null) {
                return null;
            }
            return switch (idx) {
                case 0 -> usage.getPromptTokens();
                case 1 -> usage.getCompletionTokens();
                default -> usage.getTotalTokens();
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ChatResponse callCandidate(RouteConfig cfg, String candidate, Prompt prompt) {
        RouteConfig.ModelPlatform platform = cfg.platformFor(candidate)
                .orElseThrow(() -> new IllegalArgumentException("候选平台未注册: " + candidate));
        ChatModel model = registry.getOrCreate(platform, candidate);
        String modelName = candidate.substring(candidate.indexOf('/') + 1);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(templateOptions.getTemperature())
                .tools(dynamicTools())
                .build();
        Prompt candidatePrompt = new Prompt(prompt.getInstructions(), options);
        // 每个候选独立套 llm 容错（重试/熔断/限流/超时）；候选间降级在链层，不重复重试
        return resilience.execute(ResilienceTarget.LLM, () -> model.call(candidatePrompt));
    }

    /**
     * 动态工具定义：每次调用从 ToolRegistry 实时读取（本地工具 + 动态注册的能力），
     * 覆盖装配期静态快照——能力注册/摘除后，模型下一轮即可见/不可见，无需重启。
     */
    private List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> dynamicTools() {
        if (toolRegistry == null) {
            return templateOptions.getTools();
        }
        return toolRegistry.all().stream()
                .map(com.cosy.agent.agent.tool.AgentToolBridging::toFunctionTool)
                .toList();
    }

    /** 可降级异常判定：连接失败 / 超时 / 5xx / 429 / 熔断打开 → true；4xx 等 → false */
    private boolean isFallbackable(Throwable e) {
        Throwable current = e;
        while (current != null && current.getCause() != current) {
            if (current instanceof CallNotPermittedException
                    || current instanceof TimeoutException
                    || current instanceof ResourceAccessException) {
                return true;
            }
            if (current instanceof RestClientException) {
                Integer status = httpStatus(current);
                if (status != null) {
                    return status >= 500 || status == 429;
                }
                return true; // 无状态码的 RestClient 异常按网络/协议错误处理
            }
            current = current.getCause();
        }
        return false;
    }

    private Integer httpStatus(Throwable e) {
        try {
            var method = e.getClass().getMethod("getStatusCode");
            if (method != null && method.getReturnType().getName().contains("HttpStatusCode")) {
                Object value = method.invoke(e);
                if (value instanceof org.springframework.http.HttpStatusCode status) {
                    return status.value();
                }
            }
        } catch (Exception ignored) {
            // 反射失败按未知状态处理
        }
        // Spring 6.1+ 常见实现：RestClientResponseException 带 statusCode / getStatusCode().value()
        Throwable current = e;
        while (current != null && current.getCause() != current) {
            try {
                java.lang.reflect.Method m = current.getClass().getMethod("getStatusCode");
                if (m.getReturnType().isPrimitive() || m.getReturnType() == Integer.class) {
                    return (Integer) m.invoke(current);
                }
            } catch (Exception ignored) {
                // continue
            }
            current = current.getCause();
        }
        return null;
    }

    private String classify(Throwable e) {
        Throwable current = e;
        while (current != null && current.getCause() != current) {
            if (current instanceof CallNotPermittedException) {
                return "熔断打开";
            }
            if (current instanceof TimeoutException) {
                return "调用超时";
            }
            if (current instanceof ResourceAccessException) {
                return "连接失败: " + current.getMessage();
            }
            if (current instanceof RestClientException) {
                Integer status = httpStatus(current);
                return "HTTP " + (status == null ? "未知" : status) + ": " + current.getMessage();
            }
            current = current.getCause();
        }
        return Optional.ofNullable(current == null ? e.getMessage() : current.getMessage())
                .orElse(e.getClass().getSimpleName());
    }
}
