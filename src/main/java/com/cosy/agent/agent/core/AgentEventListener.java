package com.cosy.agent.agent.core;

/**
 * Agent 流式事件监听器：ReAct 执行过程的事件回调（SSE 流式接口数据源）。
 * 同步接口（chat）不装配监听器；事件点不改变执行控制流。
 */
@FunctionalInterface
public interface AgentEventListener {

    /** 收到一次执行事件；实现方负责容错（发送失败不应中断 Agent 执行） */
    void onEvent(AgentStreamEvent event);
}
