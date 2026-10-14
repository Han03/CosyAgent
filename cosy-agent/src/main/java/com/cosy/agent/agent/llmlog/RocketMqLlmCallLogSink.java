package com.cosy.agent.agent.llmlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RocketMQ 异步通道：flush 批量经 StreamBridge 发送到 topic {@code llm-call-log}，
 * 由 agent 内消费端（{@code llmCallLogConsumer}）落 MySQL——MQ 提供持久缓冲与重试。
 * 激活条件：{@code cosy.agent.llmlog.async=rocketmq}。
 *
 * <p>序列化说明：binder 对 POJO 的默认转换（fastjson）会把 Instant 输出为 epoch 毫秒数字，
 * 而消费端按秒解析会错位（约 +58000 年）。故此处先用 Jackson 显式序列化为 ISO 字符串的
 * JSON 文本再发送，消费端以字符串接收并按同一规范解析，规避两端时间格式不一致。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.llmlog", name = "async", havingValue = "rocketmq")
public class RocketMqLlmCallLogSink implements LlmCallLogSink {

    private static final Logger log = LoggerFactory.getLogger(RocketMqLlmCallLogSink.class);
    private static final String OUTPUT_BINDING = "llm-call-log-out";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final StreamBridge streamBridge;

    public RocketMqLlmCallLogSink(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Override
    public void accept(List<LlmCallLog> batch) {
        try {
            String json = MAPPER.writeValueAsString(batch);
            log.info("LLM 调用记录 MQ 发送 {} 条", batch.size());
            if (!streamBridge.send(OUTPUT_BINDING, json)) {
                log.warn("LLM 调用记录发送 RocketMQ 失败（发送未受理），丢弃 {} 条", batch.size());
            }
        } catch (JsonProcessingException e) {
            log.warn("LLM 调用记录序列化失败，丢弃 {} 条: {}", batch.size(), e.getMessage());
        } catch (RuntimeException e) {
            // 消息中间件不可达：丢弃本批并告警，绝不阻断业务调用链（与 memory 模式语义一致）
            log.warn("LLM 调用记录发送 RocketMQ 异常，丢弃 {} 条: {}", batch.size(), e.getMessage());
        }
    }
}
