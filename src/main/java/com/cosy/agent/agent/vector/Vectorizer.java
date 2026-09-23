package com.cosy.agent.agent.vector;

/**
 * 向量化器：文本 → 归一化向量（供知识库入库与检索）。
 * 实现须确定性（同文本同向量），维度与命名空间无关。
 */
public interface Vectorizer {

    float[] vectorize(String text);
}
