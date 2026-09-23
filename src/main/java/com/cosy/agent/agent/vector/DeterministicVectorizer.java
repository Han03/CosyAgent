package com.cosy.agent.agent.vector;

import org.springframework.stereotype.Component;

/**
 * 确定性向量化器：字符 2-gram 哈希词袋 + L2 归一化（256 维）。
 * 无外部 Embedding 依赖、确定性可复现；相似文本余弦得分显著高于无关文本，
 * 用于本地知识库演示与集成测试。生产可替换为 EmbeddingModel 向量化器（同接口）。
 */
@Component
public class DeterministicVectorizer implements Vectorizer {

    private static final int DIM = 256;

    @Override
    public float[] vectorize(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String normalized = text.toLowerCase().replaceAll("\\s+", "");
        for (int i = 0; i < normalized.length(); i++) {
            String gram = normalized.substring(i, Math.min(i + 2, normalized.length()));
            vector[Math.floorMod(gram.hashCode(), DIM)] += 1f;
        }
        double sumSquares = 0;
        for (float value : vector) {
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) norm;
            }
        }
        return vector;
    }
}
