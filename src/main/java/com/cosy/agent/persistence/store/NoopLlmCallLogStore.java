package com.cosy.agent.persistence.store;

import com.cosy.agent.agent.llmlog.LlmCallLog;
import com.cosy.agent.agent.llmlog.LlmCallLogStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 调用记录兜底（cosy.agent.persistence=memory 时装配，与 MybatisLlmCallLogStore 互斥）：
 * 采集流程照常（CallRecorder 队列），落库为 no-op——memory 模式不落库、不连 MySQL。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store",
        havingValue = "memory", matchIfMissing = true)
public class NoopLlmCallLogStore implements LlmCallLogStore {

    @Override
    public void saveAll(List<LlmCallLog> logs) {
        // memory 模式：不落库
    }

    @Override
    public PageResult page(int page, int size, String sessionId, String model,
                           String routeType, String status, Instant start, Instant end) {
        return new PageResult(0, List.of());
    }

    @Override
    public Optional<LlmCallLog> findById(long id) {
        return Optional.empty();
    }

    @Override
    public List<Map<String, Object>> aggregate(String groupBy, Instant start, Instant end,
                                               String sessionId, String model,
                                               String routeType, String status) {
        return List.of();
    }

    @Override
    public Map<String, com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats> statsLast7Days() {
        return Map.of();
    }
}
