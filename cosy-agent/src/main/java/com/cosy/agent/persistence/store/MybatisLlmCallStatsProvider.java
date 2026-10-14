package com.cosy.agent.persistence.store;

import com.cosy.agent.agent.router.LlmCallStatsProvider;
import com.cosy.agent.persistence.mapper.LlmCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * MyBatis-Plus LLM 调用统计（cosy.agent.persistence=mysql 时装配，替换 JdbcLlmCallStatsProvider）：
 * 查询 llm_call_log 近 7 天按 chosen_model 聚合，供自动路由 L2 可靠性因子。
 * 查询失败返回空 map（L2 取中性值，不影响路由可用性）。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store", havingValue = "mysql")
public class MybatisLlmCallStatsProvider implements LlmCallStatsProvider {

    private static final Logger log = LoggerFactory.getLogger(MybatisLlmCallStatsProvider.class);

    private final LlmCallLogMapper mapper;

    public MybatisLlmCallStatsProvider(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, ModelStats> statsLast7Days() {
        Map<String, ModelStats> result = new HashMap<>();
        try {
            for (Map<String, Object> row : mapper.statsLast7Days(
                    Timestamp.from(Instant.now().minus(Duration.ofDays(7))))) {
                long calls = ((Number) row.get("calls")).longValue();
                long success = ((Number) row.get("success")).longValue();
                long degraded = ((Number) row.get("degraded")).longValue();
                result.put((String) row.get("chosen_model"), new ModelStats(
                        calls,
                        calls == 0 ? 0 : (double) success / calls,
                        calls == 0 ? 0 : (double) degraded / calls));
            }
        } catch (RuntimeException e) {
            // llm_call_log 不存在或查询异常：按无数据处理（L2 取中性值）
            log.debug("LLM 调用统计查询不可用: {}", e.getMessage());
            return Map.of();
        }
        return result;
    }
}
