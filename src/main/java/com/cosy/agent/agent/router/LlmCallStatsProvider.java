package com.cosy.agent.agent.router;

import java.util.Map;

/**
 * LLM 调用统计数据源（自动路由 L2 可靠性因子）。
 *
 * <p>数据来自 llm_call_log（大模型调用记录方案的表）：近 7 天按最终命中模型
 * 聚合调用次数/成功率/降级率。无数据时返回空 map——{@link ScoringResolver}
 * 对无数据候选取中性值 0.5，不影响上线。</p>
 */
public interface LlmCallStatsProvider {

    /** 无统计数据时的中性值（可靠性 = 0.5，不影响排序偏向） */
    ModelStats NEUTRAL = new ModelStats(0, 0.5, 0.5);

    /** 近 7 天按 chosen_model 聚合；无记录/不可用时返回空 map */
    Map<String, ModelStats> statsLast7Days();

    record ModelStats(long calls, double successRate, double degradeRate) {

        public ModelStats {
            successRate = clamp(successRate);
            degradeRate = clamp(degradeRate);
        }

        private static double clamp(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }

        /** 综合可靠性分：成功率 70% + 免降级率 30%（与设计文档口径一致） */
        public double reliability() {
            return 0.7 * successRate + 0.3 * (1 - degradeRate);
        }
    }
}
