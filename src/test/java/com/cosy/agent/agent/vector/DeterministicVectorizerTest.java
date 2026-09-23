package com.cosy.agent.agent.vector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicVectorizerTest {

    private final DeterministicVectorizer vectorizer = new DeterministicVectorizer();

    @Test
    void isDeterministicAndNormalized() {
        float[] first = vectorizer.vectorize("如何重置企业账号密码");
        float[] second = vectorizer.vectorize("如何重置企业账号密码");
        assertThat(first).isEqualTo(second);

        double sumSquares = 0;
        for (float value : first) {
            sumSquares += value * value;
        }
        assertThat(sumSquares).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void similarTextScoresHigherThanUnrelated() {
        float[] query = vectorizer.vectorize("如何重置密码");
        float[] related = vectorizer.vectorize("重置密码的操作步骤说明");
        float[] unrelated = vectorizer.vectorize("今天上海天气如何");

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
