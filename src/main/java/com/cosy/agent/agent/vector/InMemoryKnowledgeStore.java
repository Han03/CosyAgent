package com.cosy.agent.agent.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量知识库（默认实现，cosy.agent.vector.store=memory，无外部依赖）：
 * 命名空间隔离 + 余弦相似度检索（TopK + 最小分数阈值）。
 * 用于本地全链路演示与集成测试；生产形态见 PgVectorKnowledgeStore。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.vector", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeStore implements VectorKnowledgeStore {

    private final Map<String, List<DocVector>> namespaces = new ConcurrentHashMap<>();
    private final Vectorizer vectorizer;

    public InMemoryKnowledgeStore(Vectorizer vectorizer) {
        this.vectorizer = vectorizer;
    }

    @Override
    public void upsert(String namespace, String docId, String content, Map<String, Object> metadata) {
        List<DocVector> docs = namespaces.computeIfAbsent(namespace, key -> new java.util.concurrent.CopyOnWriteArrayList<>());
        docs.removeIf(doc -> doc.docId().equals(docId));
        docs.add(new DocVector(docId, content, vectorizer.vectorize(content)));
    }

    @Override
    public List<KnowledgeHit> search(String namespace, String query, int topK, double minScore) {
        float[] queryVector = vectorizer.vectorize(query);
        List<DocVector> docs = namespaces.getOrDefault(namespace, List.of());
        return docs.stream()
                .map(doc -> new KnowledgeHit(doc.docId(), doc.content(), cosine(doc.embedding(), queryVector)))
                .filter(hit -> hit.score() >= minScore)
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(Math.max(0, topK))
                .toList();
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private record DocVector(String docId, String content, float[] embedding) {}
}
