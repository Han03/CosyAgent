package com.cosy.agent.agent.vector;

import com.cosy.agent.TestResilience;
import com.cosy.agent.config.VectorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PGVector 真实集成测试：需要本地 PostgreSQL + pgvector 扩展（PGVECTOR_IT=true 时执行，默认跳过）。
 * 覆盖：入库/幂等更新、语义检索、命名空间隔离。
 */
@EnabledIfEnvironmentVariable(named = "PGVECTOR_IT", matches = "true")
class PgVectorKnowledgeStoreIntegrationTest {

    private PgVectorKnowledgeStore store;

    @BeforeEach
    void initStore() {
        String url = System.getenv().getOrDefault("PGVECTOR_IT_URL", "jdbc:postgresql://localhost:5432/cosy");
        VectorProperties properties = new VectorProperties("pgvector", "default", 5, 0.15, 600, 50,
                new VectorProperties.Pg(url, "postgres", "postgres"));
        store = new PgVectorKnowledgeStore(properties, TestResilience.defaultResilience());
    }

    @Test
    void upsertThenSearchReturnsRelevantHit() {
        String ns = "it-ns-a";
        store.upsert(ns, "doc-1", "重置密码的操作步骤：进入设置页点击重置并验证身份。", Map.of("docId", "doc-1"));
        store.upsert(ns, "doc-2", "服务器部署手册：配置防火墙与反向代理。", Map.of("docId", "doc-2"));

        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search(ns, "如何重置密码", 5, 0.15);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).docId()).isEqualTo("doc-1");
    }

    @Test
    void upsertReplacesSameDocIdIdempotently() {
        String ns = "it-ns-b";
        store.upsert(ns, "doc-3", "旧内容：密码找回走客服通道。", Map.of());
        store.upsert(ns, "doc-3", "新内容：密码找回通过邮箱验证码。", Map.of());

        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search(ns, "邮箱验证码 找回密码", 5, 0.2);

        assertThat(hits).extracting(VectorKnowledgeStore.KnowledgeHit::content)
                .containsExactly("新内容：密码找回通过邮箱验证码。");
    }

    @Test
    void isolatesNamespaces() {
        String ns = "it-ns-c";
        store.upsert(ns, "doc-4", "数据库备份策略：每日凌晨全量备份。", Map.of());

        List<VectorKnowledgeStore.KnowledgeHit> hits = store.search("other-ns", "数据库备份策略", 5, 0.0);

        assertThat(hits).isEmpty();
    }
}
