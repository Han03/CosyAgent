package com.cosy.agent.agent.router;

/**
 * 自动路由决策器（可插拔）：auto 模式下由 {@link AutoRouter} 按配置档位分发到具体实现。
 *
 * <p>实现约定：只做决策、不执行调用；返回的候选链必须是
 * {@link RouteConfig#resolveCandidates} 语义下的合法链（已注册平台、截断上限）；
 * 决策异常由 {@link AutoRouter} 兜底回退静态链，不得抛给调用方。</p>
 */
public interface RouteDecisionProvider {

    RouteDecision decide(RouteContext ctx);
}
