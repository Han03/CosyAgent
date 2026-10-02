package com.cosy.agent.agent.router;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动路由策略配置（来源：YAML 基线 {@code cosy.agent.model-routing} auto 段 + 管理 API 热更新）。
 *
 * @param resolver 决策档位：static（现状静态链）| task-tag（L1 标签路由）| scoring（L2 画像打分）
 * @param rules    L1 规则表（配置顺序 = 优先级，首个命中生效；结构化字段，不写死代码）
 * @param weights  L2 打分权重（键：reasoning/tools/context/reliability/speed/price）
 */
public record AutoConfig(
        String resolver,
        List<AutoRule> rules,
        Map<String, Double> weights) {

    /** 决策档位常量 */
    public static final String RESOLVER_STATIC = "static";
    public static final String RESOLVER_TASK_TAG = "task-tag";
    public static final String RESOLVER_SCORING = "scoring";

    /** L1 规则：任一条件满足即命中（minTools / maxTokenRatio / keywords；值为 0/空 = 该条件不启用） */
    public record AutoRule(String tag, int minTools, double maxTokenRatio, List<String> keywords) {
        public AutoRule {
            tag = (tag == null || tag.isBlank()) ? "default" : tag.trim();
            minTools = Math.max(0, minTools);
            maxTokenRatio = Math.max(0, maxTokenRatio); // 0 = 禁用比例条件
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    /** L2 打分权重默认值（求和 1.0） */
    public static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "reasoning", 0.30,
            "tools", 0.20,
            "context", 0.20,
            "reliability", 0.15,
            "speed", 0.10,
            "price", 0.05);

    public AutoConfig {
        resolver = (resolver == null || resolver.isBlank()) ? RESOLVER_STATIC : resolver.trim();
        rules = rules == null ? List.of() : List.copyOf(rules);
        Map<String, Double> merged = new LinkedHashMap<>(DEFAULT_WEIGHTS);
        if (weights != null) {
            weights.forEach((k, v) -> {
                if (k != null && v != null && v >= 0) {
                    merged.put(k.trim(), v);
                }
            });
        }
        weights = Map.copyOf(merged);
    }

    /** 默认策略：static 档位（行为与 v2.0 完全一致） */
    public static AutoConfig defaults() {
        return new AutoConfig(RESOLVER_STATIC, List.of(), Map.of());
    }

    public double weight(String key) {
        return weights.getOrDefault(key, 0.0);
    }
}
