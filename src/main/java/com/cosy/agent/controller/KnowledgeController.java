package com.cosy.agent.controller;

import com.cosy.agent.agent.vector.DocumentChunker;
import com.cosy.agent.agent.vector.VectorKnowledgeStore;
import com.cosy.agent.common.api.Result;
import com.cosy.agent.config.VectorProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库接口：文档入库（切分 → 向量化 → upsert）与检索。
 * 默认命名空间取 cosy.agent.vector.namespace，可显式指定实现业务域/租户隔离。
 */
@RestController
@RequestMapping("/api/agent/knowledge")
public class KnowledgeController {

    private final DocumentChunker chunker;
    private final VectorKnowledgeStore vectorStore;
    private final VectorProperties properties;

    public KnowledgeController(DocumentChunker chunker, VectorKnowledgeStore vectorStore, VectorProperties properties) {
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @PostMapping("/upsert")
    public Result<Map<String, Object>> upsert(@RequestBody UpsertRequest request) {
        String namespace = request.namespace() == null ? properties.namespace() : request.namespace();
        String docId = request.docId() == null ? "doc-" + System.nanoTime() : request.docId();
        List<String> chunks = chunker.chunk(request.content(), properties.chunkSize(), properties.overlap());
        int chunkSize = properties.chunkSize();
        int overlap = properties.overlap();
        for (int i = 0; i < chunks.size(); i++) {
            vectorStore.upsert(namespace, docId + "#" + i, chunks.get(i), Map.of("docId", docId, "chunk", i));
        }
        return Result.ok(Map.of("namespace", namespace, "docId", docId, "chunks", chunks.size(),
                "chunkSize", chunkSize, "overlap", overlap));
    }

    @PostMapping("/search")
    public Result<SearchResponse> search(@RequestBody SearchRequest request) {
        String namespace = request.namespace() == null ? properties.namespace() : request.namespace();
        int topK = request.topK() == null ? properties.topK() : request.topK();
        List<VectorKnowledgeStore.KnowledgeHit> hits =
                vectorStore.search(namespace, request.query(), topK, properties.minScore());
        return Result.ok(new SearchResponse(namespace, hits));
    }

    public record UpsertRequest(String namespace, String docId, String content) {}
    public record SearchRequest(String namespace, String query, Integer topK) {}
    public record SearchResponse(String namespace, List<VectorKnowledgeStore.KnowledgeHit> hits) {}
}
