package com.cosy.agent.agent.mock;

/**
 * 剧本回合类型。
 */
public enum MockTurnType {
    /** 模型输出工具调用意图（含候选动作，引擎随机挑一） */
    TOOL_CALL,
    /** 模型输出最终回答（从文案变体池随机挑一） */
    FINAL_ANSWER,
    /** 模型调用异常（触发编排层 FAILED 终止路径） */
    RAISE_ERROR
}
