package com.cosy.agent.agent.mock;

import java.util.List;

/**
 * 剧本：任务语义回合序列。
 *
 * @param id          剧本标识
 * @param description 任务描述（供演示/日志）
 * @param turns       回合主干（按序推进）
 * @param injectable  是否允许随机行为注入（self-heal / loop-limit 关闭，保证确定性）
 * @param loopForever 是否持续工具调用直至编排层超迭代终止（loop-limit 剧本）
 */
public record MockScript(String id, String description, List<MockTurn> turns, boolean injectable, boolean loopForever) {

    public static MockScript normal(String id, String description, List<MockTurn> turns) {
        return new MockScript(id, description, turns, true, false);
    }

    public static MockScript deterministic(String id, String description, List<MockTurn> turns) {
        return new MockScript(id, description, turns, false, false);
    }

    public static MockScript loopLimit(String id, String description, List<MockTurn> turns) {
        return new MockScript(id, description, turns, false, true);
    }
}
