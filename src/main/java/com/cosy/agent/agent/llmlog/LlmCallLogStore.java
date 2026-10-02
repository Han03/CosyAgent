package com.cosy.agent.agent.llmlog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 大模型调用记录存取接口。
 * 写入：批量落库（异步队列 flush）；读取：分页列表 / 详情 / 聚合统计。
 */
public interface LlmCallLogStore {

    /** 批量写入（flush 时调用；单条失败不影响其余） */
    void saveAll(List<LlmCallLog> logs);

    /** 分页查询（条件均为可空过滤）；返回 {total, items} */
    PageResult page(int page, int size, String sessionId, String model,
                    String routeType, String status, Instant start, Instant end);

    /** 单条详情 */
    Optional<LlmCallLog> findById(long id);

    /**
     * 聚合统计（groupBy=day|model|routeType|status；时间区间必填）；
     * 返回按组排序的指标列表（calls/successRate/degraded/avg/max/p95/tokens）。
     */
    List<Map<String, Object>> aggregate(String groupBy, Instant start, Instant end,
                                        String sessionId, String model,
                                        String routeType, String status);

    /**
     * 聚合统计（近 7 天按 chosen_model 计算可靠性因子，供自动路由 L2）。
     * 表不存在/异常返回空 map。
     */
    Map<String, com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats> statsLast7Days();

    record PageResult(long total, List<LlmCallLog> items) {
    }
}
