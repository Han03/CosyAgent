/**
 * 模型路由（Step R）：多平台候选链 + 顺序降级。配置权威在后端
 * （YAML 基线 + 管理 API 热更新，可持久化到 memory / mysql）。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.router.RouteConfig} —— 配置模型（平台 + 路由链 + 候选解析）</li>
 *   <li>{@link com.cosy.agent.agent.router.ModelRoutingConfigStore} —— 配置持久化接口，
 *       实现 {@link com.cosy.agent.agent.router.InMemoryModelRoutingConfigStore} /
 *       {@link com.cosy.agent.persistence.store.MybatisModelRoutingConfigStore}</li>
 *   <li>{@link com.cosy.agent.agent.router.ModelPlatformRegistry} —— ChatModel 工厂
 *       （按平台延迟构建 + 缓存，配置变更自动重建）</li>
 *   <li>{@link com.cosy.agent.agent.router.ModelRouter} —— 核心：解析候选链 → 依次调用 →
 *       失败降级（连接失败 / 超时 / 5xx / 429 / 熔断），4xx 不降级</li>
 *   <li>{@link com.cosy.agent.agent.router.ModelRoutingAdmin} —— 管理 API（GET/PUT 配置、目录）</li>
 * </ol>
 *
 * <p><b>关键语义</b>：请求头 {@code X-Cosy-Model: auto | platform/model}——
 * auto 走 default 链按序降级；指定模型锁定单候选，不跨模型降级。
 * Mock 开启时经 agent.mock，不经真实平台。</p>
 */
package com.cosy.agent.agent.router;
