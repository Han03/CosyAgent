package com.cosy.knowledge.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量知识库契约：命名空间隔离的文档 upsert 与相似度检索。
 */
public interface VectorKnowledgeStore {

    void upsert(String namespace, String docId, String content, Map<String, Object> metadata);

    List<KnowledgeHit> search(String namespace, String query, int topK, double minScore);

    /** 检索命中 */
    record KnowledgeHit(String docId, String content, double score) {}
}
