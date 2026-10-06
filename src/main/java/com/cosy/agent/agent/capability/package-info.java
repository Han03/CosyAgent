/**
 * 能力注册中心（参考 Nacos AP/CP 双模式）：外部项目将业务能力注册到 CosyAgent，
 * Agent 在 ReAct 循环中像调用本地工具一样调用远程能力。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.capability.Capability} / {@link com.cosy.agent.agent.capability.CapabilityProvider} /
 *       {@link com.cosy.agent.agent.capability.CapabilityStatus} —— 核心模型（能力 = Agent 的一个远程工具）</li>
 *   <li>{@link com.cosy.agent.agent.capability.CapabilityStore} —— 持久化接口，
 *       实现 {@link com.cosy.agent.agent.capability.MemoryCapabilityStore}（默认）/
 *       {@link com.cosy.agent.persistence.store.MybatisCapabilityStore}（persistence=mysql，CP 落库 + 启动恢复）</li>
 *   <li>{@link com.cosy.agent.agent.capability.CapabilityRegistry} —— 核心：注册/心跳/注销/寻址候选链/
 *       心跳摘除(AP)/主动探测(CP)/工具动态同步</li>
 *   <li>{@link com.cosy.agent.agent.capability.CapabilityProxyTool} —— 远程能力代理工具
 *       （标准 AgentTool，注入 ToolRegistry 对 LLM 透明）</li>
 *   <li>{@link com.cosy.agent.agent.capability.CapabilityController} —— 注册/心跳/注销/目录 API</li>
 * </ol>
 *
 * <p><b>AP/CP 语义</b>：AP（临时）注册即成功、心跳 5s 续约、15s 可疑、30s 摘除、最终一致；
 * CP（持久）落库确认、不因心跳丢失摘除、显式注销才移除、强一致。
 * 同能力多提供者构成候选链（UP 优先），调用失败标 DOWN 并降级，语义与模型路由候选链同构。</p>
 */
package com.cosy.agent.agent.capability;
