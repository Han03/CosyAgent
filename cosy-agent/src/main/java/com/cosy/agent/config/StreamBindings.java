package com.cosy.agent.config;

import com.cosy.agent.agent.capability.CapabilityChangeEvent;
import com.cosy.agent.agent.capability.CapabilitySync;
import com.cosy.agent.agent.llmlog.LlmCallLog;
import com.cosy.agent.agent.llmlog.LlmCallLogStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Consumer;

/**
 * P4.1 消息消费绑定（Spring Cloud Stream 函数式绑定）：
 *
 * <ul>
 *   <li>{@code llmCallLogConsumer}：topic {@code llm-call-log} → MySQL 落库
 *       （async=rocketmq 时启用；async=memory 时直落不经 MQ，无需消费）</li>
 *   <li>{@code capabilityChangeConsumer}：topic {@code capability-change} →
 *       触发能力清单立即同步（能力注册/摘除即时收敛，周期兜底保留）</li>
 * </ul>
 *
 * <p>对应 binding：{@code llmCallLogConsumer-in-0} / {@code capabilityChangeConsumer-in-0}
 * （函数名 + {@code -in-0} 后缀），destination 与 group 在配置中声明。</p>
 */
@Configuration
public class StreamBindings {

    private static final Logger log = LoggerFactory.getLogger(StreamBindings.class);
    /** 消费端独立 JSON 解析器（避免依赖容器 ObjectMapper 装配与泛型擦除） */
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Bean
    @ConditionalOnProperty(prefix = "cosy.agent.llmlog", name = "async", havingValue = "rocketmq")
    public Consumer<String> llmCallLogConsumer(LlmCallLogStore store) {
        // RocketMQ binder 对 List 泛型存在擦除（元素还原为 JSONObject），
        // 故以原始 JSON 字符串接收，由 Jackson 显式按 TypeReference 解析，规避 ClassCastException。
        return payload -> {
            try {
                List<LlmCallLog> batch = MAPPER.readValue(payload, new TypeReference<List<LlmCallLog>>() {});
                log.info("LLM 调用记录 MQ 消费 {} 条", batch.size());
                store.saveAll(batch);
            } catch (JsonProcessingException e) {
                log.warn("LLM 调用记录 MQ 消费 JSON 解析失败: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.warn("LLM 调用记录 MQ 消费落库失败（{} 条）: {}", payload.length(), e.getMessage());
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "cosy.agent.capability", name = "enabled", havingValue = "true")
    public Consumer<CapabilityChangeEvent> capabilityChangeConsumer(CapabilitySync sync) {
        return event -> {
            log.debug("能力变更事件: action={} provider={} capability={}",
                    event.action(), event.provider(), event.capability());
            sync.syncNow();
        };
    }
}
