package com.cosy.agent.agent.vector;

import com.cosy.agent.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 知识库服务客户端（P3.1）：经 Nacos 负载均衡调用 cosy-knowledge 的
 * /api/agent/knowledge（与拆分前单体的 REST 契约一致）。
 */
@FeignClient(name = "cosy-knowledge", path = "/api/agent/knowledge")
public interface KnowledgeClient {

    @PostMapping("/upsert")
    Result<Map<String, Object>> upsert(@RequestBody UpsertRequest request);

    @PostMapping("/search")
    Result<SearchResponse> search(@RequestBody SearchRequest request);

    record UpsertRequest(String namespace, String docId, String content) {}
    record SearchRequest(String namespace, String query, Integer topK) {}
    record SearchResponse(String namespace, List<VectorKnowledgeStore.KnowledgeHit> hits) {}
}
