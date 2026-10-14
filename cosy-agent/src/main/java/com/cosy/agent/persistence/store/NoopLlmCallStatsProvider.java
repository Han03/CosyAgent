package com.cosy.agent.persistence.store;

import com.cosy.agent.agent.router.LlmCallStatsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 调用统计兜底（cosy.agent.persistence=memory 时装配，与 MybatisLlmCallStatsProvider 互斥）：
 * 无数据 → 返回空 map，自动路由 L2 对无数据候选取中性值 0.5。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store",
        havingValue = "memory", matchIfMissing = true)
public class NoopLlmCallStatsProvider implements LlmCallStatsProvider {

    @Override
    public Map<String, ModelStats> statsLast7Days() {
        return Map.of();
    }
}
