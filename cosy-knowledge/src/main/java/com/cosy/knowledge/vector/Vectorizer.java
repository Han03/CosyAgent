package com.cosy.knowledge.vector;

/**
 * 向量化器契约：文本 → 定长向量（余弦相似度检索的基础）。
 */
public interface Vectorizer {

    float[] vectorize(String text);
}
