package com.cosy.agent.config;

import com.cosy.agent.agent.router.AutoConfig;
import com.cosy.agent.agent.router.AutoConfigHolder;
import com.cosy.agent.agent.router.AutoRouter;
import com.cosy.agent.agent.router.ModelRouter;
import com.cosy.agent.agent.router.ModelRoutingConfigStore;
import com.cosy.agent.agent.router.RouteConfig;
import com.cosy.agent.agent.router.ScoringResolver;
import com.cosy.agent.agent.router.TaskTagResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 模型路由装配：以 YAML 基线构造 {@link ModelRouter}；持久化 store 有覆盖时以覆盖为准。
 * 关闭路由（enabled=false）时 Router 内部回退默认单模型（原行为）。
 * v2.1：装配自动路由决策层（static/task-tag/scoring 档位，YAML auto 段初始化）。
 */
@Configuration
public class ModelRoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingConfig.class);

    @Bean
    public ModelRouter modelRouter(ChatModel defaultChatModel, OpenAiChatOptions agentChatOptions,
                                   ModelRoutingProperties properties,
                                   com.cosy.agent.agent.resilience.ResilienceSupport resilience,
                                   ModelRoutingConfigStore configStore,
                                   com.cosy.agent.agent.tool.ToolRegistry toolRegistry,
                                   AutoRouter autoRouter) {
        RouteConfig baseline = RouteConfig.fromProperties(properties);
        RouteConfig initial = configStore.load().orElse(baseline);
        if (configStore.load().isPresent()) {
            log.info("模型路由配置来自持久化 store（覆盖 YAML 基线）");
        }
        // 传入 ToolRegistry：每轮调用动态注入工具定义（本地工具 + 能力注册中心远程能力）
        ModelRouter router = new ModelRouter(defaultChatModel, agentChatOptions, resilience, initial,
                new com.cosy.agent.agent.router.ModelPlatformRegistry(agentChatOptions), toolRegistry);
        router.setAutoRouter(autoRouter);
        log.info("模型路由初始化: enabled={}, routes={}, autoResolver={}",
                initial.enabled(), initial.routes().keySet(), properties.autoResolver());
        return router;
    }

    /** 自动路由策略持有者（YAML auto 段初始化，管理 API 可热更新） */
    @Bean
    public AutoConfigHolder autoConfigHolder(ModelRoutingProperties properties) {
        List<AutoConfig.AutoRule> rules = properties.autoRules() == null ? List.of()
                : properties.autoRules().stream()
                        .map(r -> new AutoConfig.AutoRule(r.tag(), r.minTools(),
                                r.maxTokenRatio(), r.keywords()))
                        .toList();
        return new AutoConfigHolder(new AutoConfig(
                properties.autoResolver(), rules,
                properties.scoringWeights() == null ? Map.of() : properties.scoringWeights()));
    }

    /** 自动路由编排入口（依赖：TaskTagResolver → ScoringResolver → AutoRouter） */
    @Bean
    public AutoRouter autoRouter(AutoConfigHolder holder, ModelRoutingConfigStore configStore,
                                 ModelRoutingProperties properties,
                                 com.cosy.agent.agent.router.LlmCallStatsProvider statsProvider) {
        RouteConfig baseline = RouteConfig.fromProperties(properties);
        RouteConfig config = configStore.load().orElse(baseline);
        TaskTagResolver taskTagResolver = new TaskTagResolver(config, holder);
        ScoringResolver scoringResolver = new ScoringResolver(config, holder, statsProvider, taskTagResolver);
        return new AutoRouter(holder, config, taskTagResolver, scoringResolver);
    }
}

