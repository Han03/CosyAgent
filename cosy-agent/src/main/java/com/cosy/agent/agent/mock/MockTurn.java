package com.cosy.agent.agent.mock;

import java.util.List;

/**
 * 剧本回合。
 *
 * @param type           回合类型
 * @param actions        TOOL_CALL 候选动作（引擎随机挑一）
 * @param answerVariants FINAL_ANSWER 文案变体池（引擎随机挑一；支持 {@code {result}} 占位，替换为最近一次工具观察结果）
 */
public record MockTurn(MockTurnType type, List<MockAction> actions, List<String> answerVariants) {

    public static MockTurn toolCall(MockAction... actions) {
        return new MockTurn(MockTurnType.TOOL_CALL, List.of(actions), List.of());
    }

    public static MockTurn finalAnswer(String... variants) {
        return new MockTurn(MockTurnType.FINAL_ANSWER, List.of(), List.of(variants));
    }

    public static MockTurn raiseError() {
        return new MockTurn(MockTurnType.RAISE_ERROR, List.of(), List.of());
    }
}
