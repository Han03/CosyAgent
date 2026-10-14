package com.cosy.knowledge.vector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性向量化器测试：同文本同向量、相似文本得分显著高于无关文本。
 */
class DeterministicVectorizerTest {

    private final DeterministicVectorizer vectorizer = new DeterministicVectorizer();

    @Test
    void deterministic() {
        float[] a = vectorizer.vectorize("如何重置密码");
        float[] b = vectorizer.vectorize("如何重置密码");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void similarScoresHigherThanUnrelated() {
        double similar = cosine(vectorizer.vectorize("重置密码 邮箱验证码"),
                vectorizer.vectorize("密码找回 重置密码"));
        double unrelated = cosine(vectorizer.vectorize("重置密码"),
                vectorizer.vectorize("今天天气怎么样"));
        assertThat(similar).isGreaterThan(unrelated);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
