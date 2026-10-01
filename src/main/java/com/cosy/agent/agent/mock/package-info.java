/**
 * LLM 端到端 Mock 引擎：开关开启时不经任何真实模型平台，
 * 按剧本完整模拟 ReAct 全流程（工具调用 / 自愈 / 循环终止 / 延迟），并带随机性。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.mock.MockTurnType} / {@link com.cosy.agent.agent.mock.MockTurn} /
 *       {@link com.cosy.agent.agent.mock.MockAction} —— 回合 / 动作建模</li>
 *   <li>{@link com.cosy.agent.agent.mock.MockScript} —— 剧本（任务语义回合序列）</li>
 *   <li>{@link com.cosy.agent.agent.mock.MockScriptLibrary} —— 剧本库
 *       （time / combined / server-info / self-heal / loop-limit）</li>
 *   <li>{@link com.cosy.agent.agent.mock.MockRandomSource} —— 随机源
 *       （scripted 固定种子可复现；random 以用户消息哈希为锚 + 时刻）</li>
 *   <li>{@link com.cosy.agent.agent.mock.MockScriptEngine} —— 引擎：generate(Prompt)
 *       按剧本推进回合、注入随机性、模拟延迟，产出 {@code ChatResponse}</li>
 * </ol>
 *
 * <p><b>调用方</b>：agent.router.ModelRouter 的 mock 分支（全局开关
 * {@code cosy.agent.mock.enabled}，请求头 {@code X-Cosy-Mock} 可覆盖）。</p>
 */
package com.cosy.agent.agent.mock;
