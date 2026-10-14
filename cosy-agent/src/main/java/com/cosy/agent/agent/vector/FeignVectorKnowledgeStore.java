package com.cosy.agent.agent.vector;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库远程实现（P3.1）：Feign 调用 cosy-knowledge 服务，替代原单体本地存储。
 * 失败由调用方（DefaultReActAgent RAG 注入）catch 降级跳过。
 */
@Component
public class FeignVectorKnowledgeStore implements VectorKnowledgeStore {

    private final KnowledgeClient client;

    public FeignVectorKnowledgeStore(KnowledgeClient client) {
        this.client = client;
    }

    @Override
    public void upsert(String namespace, String docId, String content, Map<String, Object> metadata) {
        client.upsert(new KnowledgeClient.UpsertRequest(namespace, docId, content));
    }

    @Override
    public List<KnowledgeHit> search(String namespace, String query, int topK, double minScore) {
        com.cosy.agent.common.api.Result<KnowledgeClient.SearchResponse> result =
                client.search(new KnowledgeClient.SearchRequest(namespace, query, topK));
        if (result == null || result.code() != 0 || result.data() == null) {
            throw new IllegalStateException("knowledge search failed: " + (result == null ? "null" : result.message()));
        }
        return result.data().hits();
    }
}
