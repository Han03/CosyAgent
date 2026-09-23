package com.cosy.agent.agent.core;

/**
 * Agent 生命周期状态。
 */
public enum AgentState {
    /** 初始创建 */
    INIT,
    /** 自主任务规划中（Step 2） */
    PLANNING,
    /** 执行推理 / 工具调用中 */
    RUNNING,
    /** 等待工具返回 */
    TOOL_CALLING,
    /** 正常完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 超过最大迭代次数 */
    TIMEOUT,
    /** 被取消 */
    CANCELLED
}
