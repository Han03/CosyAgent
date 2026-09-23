package com.cosy.agent.agent.mock;

import java.util.List;
import java.util.Random;

/**
 * 随机源：统一封装 mock 的非确定性来源。
 * 固定种子（scripted 模式）可复现；seed 为空则系统随机（random 模式）。
 */
public class MockRandomSource {

    private final Random random;

    public MockRandomSource(Long seed) {
        this.random = seed == null ? new Random() : new Random(seed);
    }

    /** 0..bound-1 随机整数 */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** 以概率 p 命中（p<=0 恒 false，p>=1 恒 true） */
    public boolean chance(double p) {
        return p > 0 && (p >= 1 || random.nextDouble() < p);
    }

    /** 从候选列表随机挑一 */
    public <T> T pick(List<T> candidates) {
        return candidates.get(nextInt(candidates.size()));
    }
}
