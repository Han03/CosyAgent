package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 模型平台注册表：按平台名注册（base-url / api-key），延迟构建并缓存
 * {@link ChatModel} 实例。平台信息（baseUrl/apiKey）变化时自动重建缓存实例
 * （缓存键含配置摘要），支持管理 API 热更新后即时切换。
 */
public class ModelPlatformRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelPlatformRegistry.class);

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
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(platform.baseUrl())
                .apiKey(platform.apiKey() == null ? "" : platform.apiKey())
                .completionsPath(platform.completionsPath())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(templateOptions.getTemperature())
                .tools(templateOptions.getTools())
                .build();
        log.info("构建模型平台实例: platform={}, model={}, baseUrl={}",
                platform.name(), model, platform.baseUrl());
        return org.springframework.ai.openai.OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
