package com.cosy.agent.agent.llmlog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内存直落通道（默认）：flush 批量直接写 MySQL。
 * 激活条件：{@code cosy.agent.llmlog.async=memory}（缺省同义，保持既有行为）。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.llmlog", name = "async",
        havingValue = "memory", matchIfMissing = true)
public class MemoryLlmCallLogSink implements LlmCallLogSink {

    private final LlmCallLogStore store;

    public MemoryLlmCallLogSink(LlmCallLogStore store) {
        this.store = store;
    }

    @Override
    public void accept(List<LlmCallLog> batch) {
        store.saveAll(batch);
    }
}
