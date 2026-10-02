package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * L1 任务标签路由：结构化规则表（{@link AutoConfig.AutoRule}，配置顺序 = 优先级）匹配
 * 输入特征（工具数量 / 上下文规模 / 用户意图关键词）→ 任务标签 → 路由链。
 *
 * <p>规则表来自配置（YAML 基线 + 管理 API 热更新），不写死代码；
 * 无规则命中回退 default 链（行为与静态链一致）。</p>
 */
public class TaskTagResolver implements RouteDecisionProvider {

    private static final Logger log = LoggerFactory.getLogger(TaskTagResolver.class);

    private final RouteConfig config;
    private final AutoConfigHolder holder;

    public TaskTagResolver(RouteConfig config, AutoConfigHolder holder) {
        this.config = config;
        this.holder = holder;
    }

    /** 命中标签（首个满足规则）；无命中返回 null（调用方回退 default） */
    public String resolveTag(RouteContext ctx) {
        AutoConfig cfg = holder.current();
        int tools = ctx.toolCount();
        int estTokens = ctx.estimatedTokens();
        int maxWindow = maxContextWindow();
        String userText = ctx.lastUserText();
        for (AutoConfig.AutoRule rule : cfg.rules()) {
            if (rule.minTools() > 0 && tools >= rule.minTools()) {
                log.debug("自动路由规则命中: tag={}, 条件=tools>={} (实际 {})", rule.tag(), rule.minTools(), tools);
                return rule.tag();
            }
            if (rule.maxTokenRatio() > 0 && maxWindow > 0
                    && estTokens > rule.maxTokenRatio() * maxWindow) {
                log.debug("自动路由规则命中: tag={}, 条件=estTokens>{} (实际 {})",
                        rule.tag(), (int) (rule.maxTokenRatio() * maxWindow), estTokens);
                return rule.tag();
            }
            if (!rule.keywords().isEmpty() && containsAny(userText, rule.keywords())) {
                log.debug("自动路由规则命中: tag={}, 条件=关键词 {}", rule.tag(), rule.keywords());
                return rule.tag();
            }
        }
        return null;
    }

    @Override
    public RouteDecision decide(RouteContext ctx) {
        String tag = resolveTag(ctx);
        // resolveCandidates(auto, tag)：tag=null 即 default 链（含 maxCandidates 截断与未注册回退）
        List<String> candidates = config.resolveCandidates(ctx.modelChoice(), tag);
        String rationale = tag == null
                ? "[task-tag] 无规则命中 → default 链"
                : "[task-tag] 命中标签 " + tag + " → " + candidates;
        return new RouteDecision(rationale, candidates, RouteDecision.RouteDecisionMode.TASK_TAG);
    }

    /** 启用平台全部已登记模型的最大上下文窗口；无画像数据返回 0（跳过比例判断） */
    private int maxContextWindow() {
        int max = 0;
        for (RouteConfig.ModelPlatform pf : config.platforms().values()) {
            if (!pf.enabled()) {
                continue;
            }
            for (RouteConfig.ModelSpec spec : pf.models()) {
                max = Math.max(max, spec.contextWindow());
            }
        }
        return max;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && lower.contains(kw.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
