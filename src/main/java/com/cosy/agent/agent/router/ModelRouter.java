package com.cosy.agent.agent.router;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<RouteConfig> config;

    public ModelRouter(ChatModel defaultChatModel, OpenAiChatOptions templateOptions,
                       ResilienceSupport resilience, RouteConfig baseline) {
        this(defaultChatModel, templateOptions, resilience, baseline,
                new ModelPlatformRegistry(templateOptions));
    }

    /** 测试/定制用：注入平台注册表 */
    ModelRouter(ChatModel defaultChatModel, OpenAiChatOptions templateOptions,
                ResilienceSupport resilience, RouteConfig baseline, ModelPlatformRegistry registry) {
        this.defaultChatModel = defaultChatModel;
        this.templateOptions = templateOptions;
        this.resilience = resilience;
        this.registry = registry;
        this.config = new AtomicReference<>(baseline);
    }

    /** 当前运行时配置（管理 API 读取用） */
    public RouteConfig currentConfig() {
        return config.get();
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
        RouteConfig cfg = config.get();
        // 兼容回退：路由关闭时走默认单模型（原行为，含 llm 容错）
        if (!cfg.enabled()) {
            ChatResponse resp = resilience.execute(ResilienceTarget.LLM, () -> defaultChatModel.call(prompt));
            return RouteResult.direct(resp, "default");
        }

        List<String> candidates = cfg.resolveCandidates(modelChoice);
        if (candidates.isEmpty()) {
            // 路由链为空：退化为默认单模型（如平台未配置）
            log.warn("模型路由候选链为空（routes 未配置），回退默认模型: choice={}", modelChoice);
            ChatResponse resp = resilience.execute(ResilienceTarget.LLM, () -> defaultChatModel.call(prompt));
            return RouteResult.direct(resp, "default");
        }

        List<String> attempts = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        RuntimeException lastFailure = null;
        for (String candidate : candidates) {
            attempts.add(candidate);
            try {
                ChatResponse resp = callCandidate(cfg, candidate, prompt);
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
                lastFailure = e;
                log.warn("模型路由候选失败，切换下一候选: candidate={}, reason={}", candidate, reason);
            }
        }
        log.error("模型路由全部候选失败: choice={}, candidates={}, reasons={}", modelChoice, candidates, reasons);
        throw new IllegalStateException("所有模型候选均调用失败: " + reasons, lastFailure);
    }

    private ChatResponse callCandidate(RouteConfig cfg, String candidate, Prompt prompt) {
        RouteConfig.ModelPlatform platform = cfg.platformFor(candidate)
                .orElseThrow(() -> new IllegalArgumentException("候选平台未注册: " + candidate));
        ChatModel model = registry.getOrCreate(platform, candidate);
        String modelName = candidate.substring(candidate.indexOf('/') + 1);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(templateOptions.getTemperature())
                .tools(templateOptions.getTools())
                .build();
        Prompt candidatePrompt = new Prompt(prompt.getInstructions(), options);
        // 每个候选独立套 llm 容错（重试/熔断/限流/超时）；候选间降级在链层，不重复重试
        return resilience.execute(ResilienceTarget.LLM, () -> model.call(candidatePrompt));
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
