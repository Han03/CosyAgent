package com.cosy.agent.agent.llmlog;

import java.util.List;

/**
 * LLM 调用记录落库通道（P4.1 异步化抽象）：flush 批量写入的统一出口。
 *
 * <ul>
 *   <li>{@code memory}：直落 MySQL（现有定时批量行为）</li>
 *   <li>{@code rocketmq}：经 RocketMQ topic {@code llm-call-log} 中转，
 *       由 agent 内消费端落 MySQL——MQ 提供持久缓冲与重试，避免内存队列满丢弃</li>
 * </ul>
 */
public interface LlmCallLogSink {

    /** 批量接收调用记录（实现内自行处理失败，不得抛出到调用链） */
    void accept(List<LlmCallLog> batch);
}
