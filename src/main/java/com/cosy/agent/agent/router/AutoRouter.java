package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 自动路由编排入口（auto 且未显式指定 routeType 时由 {@link ModelRouter} 调用）：
 * 按策略档位分发到具体决策器，决策失败/档位未知回退静态链（与 v2.0 行为一致）。
 *
 * <p>档位：static（默认，现状）| task-tag（L1）| scoring（L2，内部委托 task-tag 选链后打分排序）。
 * 显式 routeType / 指定模型不经过本类（由 ModelRouter 直接走静态路径）。</p>
 */
public class AutoRouter {

    private static final Logger log = LoggerFactory.getLogger(AutoRouter.class);

    private final AutoConfigHolder holder;
    private final RouteConfig config;
    private final TaskTagResolver taskTagResolver;
    private final ScoringResolver scoringResolver;

    public AutoRouter(AutoConfigHolder holder, RouteConfig config,
                      TaskTagResolver taskTagResolver, ScoringResolver scoringResolver) {
        this.holder = holder;
        this.config = config;
        this.taskTagResolver = taskTagResolver;
        this.scoringResolver = scoringResolver;
    }

    /**
     * 执行自动路由决策；任何异常回退静态链（决策不得影响主请求）。
     *
     * @return 决策结果；候选为空时调用方应回退 {@link RouteConfig#resolveCandidates} 静态路径
     */
    public RouteDecision decide(RouteContext ctx) {
        try {
            RouteDecision decision = switch (holder.current().resolver()) {
                case AutoConfig.RESOLVER_SCORING -> scoringResolver.decide(ctx);
                case AutoConfig.RESOLVER_TASK_TAG -> taskTagResolver.decide(ctx);
                default -> staticDecision(ctx); // static 与未知档位统一回退
            };
            log.info("自动路由决策: mode={}, candidates={}, rationale={}",
                    decision.mode(), decision.candidates(), decision.rationale());
            return decision;
        } catch (RuntimeException e) {
            log.warn("自动路由决策失败，回退静态链: {}", e.getMessage());
            return staticDecision(ctx);
        }
    }

    /** L0 静态链（现状语义：auto → default 链，含 maxCandidates 截断） */
    private RouteDecision staticDecision(RouteContext ctx) {
        List<String> candidates = config.resolveCandidates(ctx.modelChoice(), null);
        return RouteDecision.staticDecision("[static] 默认链 " + candidates, candidates);
    }
}
