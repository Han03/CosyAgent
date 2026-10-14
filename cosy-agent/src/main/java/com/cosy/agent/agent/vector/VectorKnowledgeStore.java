package com.cosy.agent.agent.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量知识库契约（agent 侧视图）：由独立服务 cosy-knowledge 提供实现
 * （FeignVectorKnowledgeStore 远程调用），ReAct 编排据此做 RAG 注入。
 */
public interface VectorKnowledgeStore {

    void upsert(String namespace, String docId, String content, Map<String, Object> metadata);

    List<KnowledgeHit> search(String namespace, String query, int topK, double minScore);

    /** 检索命中 */
    record KnowledgeHit(String docId, String content, double score) {}
}
