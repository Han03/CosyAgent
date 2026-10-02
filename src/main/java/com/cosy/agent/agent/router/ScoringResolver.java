package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 画像打分路由：委托 {@link TaskTagResolver} 选链（标签 → 候选），再对链内候选
 * 按「任务需求 × 模型画像」多因子评分**动态排序**（不再固定链序）。
 *
 * <p>打分公式（权重来自 {@link AutoConfig#weights}，默认求和 1.0）：</p>
 * <pre>
 * score(c) = w1·min(需求推理, c.reasoning)/5 + w2·min(需求工具, c.toolSupport)/5
 *          + w3·窗口适配(足够覆盖?1:0.3)       + w4·可靠性(近7d 成功率/降级率，无数据 0.5)
 *          + w5·c.speed/5 - w6·(c.priceTier-1)/4
 * </pre>
 * 排序稳定（同分保持声明顺序）；仅对链内候选排序，不引入链外模型（保持 catalog 口径）。
 */
public class ScoringResolver implements RouteDecisionProvider {

    private static final Logger log = LoggerFactory.getLogger(ScoringResolver.class);

    private final RouteConfig config;
    private final AutoConfigHolder holder;
    private final LlmCallStatsProvider statsProvider;
    private final TaskTagResolver tagResolver;

    public ScoringResolver(RouteConfig config, AutoConfigHolder holder,
                           LlmCallStatsProvider statsProvider, TaskTagResolver tagResolver) {
        this.config = config;
        this.holder = holder;
        this.statsProvider = statsProvider;
        this.tagResolver = tagResolver;
    }

    @Override
    public RouteDecision decide(RouteContext ctx) {
        RouteDecision base = tagResolver.decide(ctx);
        List<String> chain = base.candidates();
        if (chain.size() <= 1) {
            return new RouteDecision("[scored] 候选数≤1 无需排序: " + chain, chain,
                    RouteDecision.RouteDecisionMode.SCORED);
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String c : chain) {
            scores.put(c, score(ctx, c));
        }
        // 稳定排序（同分保持链声明顺序）
        List<String> sorted = new ArrayList<>(chain);
        sorted.sort(Comparator.comparingDouble((String c) -> scores.get(c)).reversed());

        StringBuilder sb = new StringBuilder("[scored] 需求推理=")
                .append(need(ctx, "reasoning")).append(" 需求工具=").append(need(ctx, "tools"));
        scores.forEach((c, s) -> sb.append(" ").append(c).append("(").append(String.format("%.2f", s)).append(")"));
        sb.append(" → ").append(sorted);
        String rationale = sb.toString();
        log.debug("自动路由打分: {}", rationale);
        return new RouteDecision(rationale, sorted, RouteDecision.RouteDecisionMode.SCORED);
    }

    /** 任务需求强度（由命中标签推导）：reasoning/tool-heavy 标签 → 4，其余 → 2 */
    private int need(RouteContext ctx, String dimension) {
        String tag = tagResolver.resolveTag(ctx);
        if ("reasoning".equals(tag) && "reasoning".equals(dimension)) {
            return 4;
        }
        if ("tool-heavy".equals(tag) && "tools".equals(dimension)) {
            return 4;
        }
        return 2;
    }

    private double score(RouteContext ctx, String candidate) {
        AutoConfig cfg = holder.current();
        RouteConfig.ModelSpec spec = specFor(candidate);
        int needReasoning = need(ctx, "reasoning");
        int needTools = need(ctx, "tools");
        double reasoning = Math.min(needReasoning, spec.reasoning()) / 5.0;
        double tools = Math.min(needTools, spec.toolSupport()) / 5.0;
        double context = spec.contextWindow() <= 0 ? 1.0
                : (ctx.estimatedTokens() <= spec.contextWindow() ? 1.0 : 0.3);
        double reliability = statsProvider.statsLast7Days()
                .getOrDefault(candidate, LlmCallStatsProvider.NEUTRAL).reliability();
        double speed = spec.speed() / 5.0;
        double price = (spec.priceTier() - 1) / 4.0;
        return cfg.weight("reasoning") * reasoning
                + cfg.weight("tools") * tools
                + cfg.weight("context") * context
                + cfg.weight("reliability") * reliability
                + cfg.weight("speed") * speed
                - cfg.weight("price") * price;
    }

    /** 候选对应画像；模型未登记时用中性画像（兜底不崩） */
    private RouteConfig.ModelSpec specFor(String candidate) {
        return config.platformFor(candidate)
                .flatMap(pf -> pf.models().stream()
                        .filter(m -> candidate.endsWith("/" + m.modelId())).findFirst())
                .orElseGet(() -> RouteConfig.ModelSpec.unknown(candidate));
    }
}
