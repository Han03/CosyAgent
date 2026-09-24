package com.cosy.agent.config;

import com.cosy.agent.agent.router.ModelRouter;
import com.cosy.agent.agent.router.ModelRoutingConfigStore;
import com.cosy.agent.agent.router.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型路由装配：以 YAML 基线构造 {@link ModelRouter}；持久化 store 有覆盖时以覆盖为准。
 * 关闭路由（enabled=false）时 Router 内部回退默认单模型（原行为）。
 */
@Configuration
public class ModelRoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingConfig.class);

    @Bean
    public ModelRouter modelRouter(ChatModel defaultChatModel, OpenAiChatOptions agentChatOptions,
                                   ModelRoutingProperties properties,
                                   com.cosy.agent.agent.resilience.ResilienceSupport resilience,
                                   ModelRoutingConfigStore configStore) {
        RouteConfig baseline = RouteConfig.fromProperties(properties);
        RouteConfig initial = configStore.load().orElse(baseline);
        if (configStore.load().isPresent()) {
            log.info("模型路由配置来自持久化 store（覆盖 YAML 基线）");
        }
        ModelRouter router = new ModelRouter(defaultChatModel, agentChatOptions, resilience, initial);
        log.info("模型路由初始化: enabled={}, routes={}", initial.enabled(), initial.routes().keySet());
        return router;
    }
}
