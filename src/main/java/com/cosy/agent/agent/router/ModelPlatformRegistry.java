package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 模型平台注册表：按平台名注册（base-url / api-key），延迟构建并缓存
 * {@link ChatModel} 实例。平台信息（baseUrl/apiKey）变化时自动重建缓存实例
 * （缓存键含配置摘要），支持管理 API 热更新后即时切换。
 *
 * <p>Spring AI 2.0 构建方式：OpenAiChatModel.Builder 内置 OpenAIClient 构建
 * （读取 options 的 baseUrl/apiKey，经 OpenAiSetup 走官方 openai-java SDK；
 * 空 apiKey 自动进入 no-auth 模式）。completionsPath 已从 API 移除，
 * SDK 固定以 baseUrl + /chat/completions 请求，故将 1.x 的
 * baseUrl + completionsPath 换算为 SDK 的 baseUrl（去掉末尾 /chat/completions）。</p>
 */
public class ModelPlatformRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelPlatformRegistry.class);

    private static final String COMPLETIONS_SUFFIX = "/chat/completions";

    private final OpenAiChatOptions templateOptions;
    private final BiFunction<RouteConfig.ModelPlatform, String, ChatModel> factory;

    /** key = platform + "#" + model + "#" + 配置摘要（baseUrl/apiKey hash），变化即重建 */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ModelPlatformRegistry(OpenAiChatOptions templateOptions) {
        this(templateOptions, (pf, m) -> defaultBuild(templateOptions, pf, m));
    }

    /** 测试/定制用：注入模型实例工厂 */
    ModelPlatformRegistry(OpenAiChatOptions templateOptions,
                          BiFunction<RouteConfig.ModelPlatform, String, ChatModel> factory) {
        this.templateOptions = templateOptions;
        this.factory = factory;
    }

    /**
     * 获取（或构建）候选模型实例。候选配置变化（管理 API 更新平台 baseUrl/apiKey）
     * 时缓存键改变 → 自动重建新实例，旧实例随 GC 释放。
     */
    public ChatModel getOrCreate(RouteConfig.ModelPlatform platform, String candidate) {
        String model = candidate.substring(candidate.indexOf('/') + 1);
        String signature = platform.name() + "#" + model + "#"
                + Integer.toHexString((platform.baseUrl() + "|" + platform.apiKey()).hashCode());
        return cache.computeIfAbsent(signature, k -> factory.apply(platform, model));
    }

    /** 配置变更后清空缓存（强制重建全部候选实例） */
    public void invalidate() {
        cache.clear();
        log.info("模型平台注册表缓存已清空（配置变更）");
    }

    private static ChatModel defaultBuild(OpenAiChatOptions templateOptions,
                                             RouteConfig.ModelPlatform platform, String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .baseUrl(sdkBaseUrl(platform.baseUrl(), platform.completionsPath()))
                .apiKey(platform.apiKey() == null ? "" : platform.apiKey())
                .temperature(templateOptions.getTemperature())
                .toolCallbacks(templateOptions.getToolCallbacks())
                .build();
        log.info("构建模型平台实例: platform={}, model={}, baseUrl={}",
                platform.name(), model, options.getBaseUrl());
        // Spring AI 2.0：ChatModel 内部工具执行循环已移除（工具执行归上层编排），
        // 模型返回的 toolCalls 原样透出，由上层 ReAct 循环统一执行
        // （未知工具自愈 / TOOL 容错 / 轨迹审计），无需再禁用内部执行。
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    /** 1.x baseUrl + completionsPath → 2.0 SDK baseUrl（SDK 固定追加 /chat/completions） */
    private static String sdkBaseUrl(String baseUrl, String completionsPath) {
        String path = completionsPath == null ? "" : completionsPath;
        String prefix = path.endsWith(COMPLETIONS_SUFFIX)
                ? path.substring(0, path.length() - COMPLETIONS_SUFFIX.length()) : path;
        String base = baseUrl == null ? "" : baseUrl;
        return base + prefix;
    }
}
