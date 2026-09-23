package com.cosy.agent.agent.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryKnowledgeStoreTest {

    private InMemoryKnowledgeStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKnowledgeStore(new DeterministicVectorizer());
        store.upsert("hr", "doc-1", "重置密码的操作步骤说明：进入设置页点击重置并验证身份。", Map.of());
        store.upsert("hr", "doc-2", "请假流程：提前一天提交申请，主管审批后生效。", Map.of());
        store.upsert("it", "doc-3", "服务器部署手册：配置防火墙与反向代理。", Map.of());
    }

    @Test
    void searchesRelevantChunksBySemanticSimilarity() {
        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search("hr", "如何重置密码", 5, 0.15);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).docId()).isEqualTo("doc-1");
    }

    @Test
    void filtersByMinScore() {
        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search("hr", "如何重置密码", 5, 0.99);
        assertThat(hits).isEmpty();
    }

    @Test
    void respectsTopK() {
        store.upsert("hr", "doc-4", "密码修改指引：重置后的临时密码当天失效。", Map.of());
        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search("hr", "密码 重置 修改 指引", 1, 0.0);
        assertThat(hits).hasSize(1);
    }

    @Test
    void isolatesNamespaces() {
        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search("it", "如何重置密码", 5, 0.0);
        assertThat(hits).allMatch(hit -> hit.docId().equals("doc-3"));
    }

    @Test
    void upsertReplacesSameDocId() {
        store.upsert("hr", "doc-1", "新内容：密码找回通过邮箱验证。", Map.of());
        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search("hr", "密码找回 邮箱验证", 5, 0.3);
        assertThat(hits).extracting(VectorKnowledgeStore.KnowledgeHit::content)
                .contains("新内容：密码找回通过邮箱验证。");
    }
}
