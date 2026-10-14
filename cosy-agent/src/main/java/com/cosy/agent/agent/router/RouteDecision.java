package com.cosy.agent.agent.router;

import java.util.List;

/**
 * 自动路由决策结果：最终有序候选链 + 决策原因（可解释、可审计）。
 *
 * @param rationale  决策原因（人类可读：命中特征/标签/分数排序）
 * @param candidates 有序候选链（按降级优先级；交给 ModelRouter 现有降级循环执行）
 * @param mode       决策模式（static=回退现状 / task-tag=L1 / scored=L2）
 */
public record RouteDecision(String rationale, List<String> candidates, RouteDecisionMode mode) {

    public enum RouteDecisionMode {
        /** L0：静态链（默认/回退，行为与 v2.0 一致） */
        STATIC,
        /** L1：任务标签路由（特征 → 标签 → 链） */
        TASK_TAG,
        /** L2：画像打分排序（标签选链 + 多因子评分动态排序） */
        SCORED
    }

    public RouteDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static RouteDecision staticDecision(String rationale, List<String> candidates) {
        return new RouteDecision(rationale, candidates, RouteDecisionMode.STATIC);
    }
}
