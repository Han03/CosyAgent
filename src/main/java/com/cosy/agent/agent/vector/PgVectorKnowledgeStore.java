package com.cosy.agent.agent.vector;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.config.VectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PGVector 向量知识库（生产实现，cosy.agent.vector.store=pgvector 时装配）：
 * PostgreSQL + pgvector 扩展，表 vector_doc（namespace、doc_id、content、metadata、embedding vector(256)），
 * 余弦距离（&lt;=&gt;）检索 + 命名空间隔离 + TopK/阈值过滤。
 *
 * <p>v2 方案改造：连接层由手写 JDBC 直连换成 {@link JdbcTemplate}（pg 连接池，
 * 见 persistence.config.PgJdbcConfig），SQL 与参数化语义不变；未安装 PostgreSQL 时
 * 应用默认 memory 模式不受影响（本类不装配）。入库与检索落在 VECTOR 容错落点。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.vector", name = "store", havingValue = "pgvector")
public class PgVectorKnowledgeStore implements VectorKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeStore.class);
    private static final int DIM = 256;

    private final JdbcTemplate jdbc;
    private final ResilienceSupport resilience;

    public PgVectorKnowledgeStore(VectorProperties properties,
                                  @Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate,
                                  ResilienceSupport resilience) {
        this.jdbc = pgJdbcTemplate;
        this.resilience = resilience;
        initSchema(properties.pg().url());
    }

    private void initSchema(String url) {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vector_doc (
                    namespace  TEXT NOT NULL,
                    doc_id     TEXT NOT NULL,
                    content    TEXT NOT NULL,
                    metadata   JSONB,
                    embedding  VECTOR(%d) NOT NULL,
                    PRIMARY KEY (namespace, doc_id)
                )
                """.formatted(DIM));
        log.info("PGVector schema ready at {}", url);
    }

    @Override
    public void upsert(String namespace, String docId, String content, Map<String, Object> metadata) {
        String sql = """
                INSERT INTO vector_doc (namespace, doc_id, content, metadata, embedding)
                VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS VECTOR))
                ON CONFLICT (namespace, doc_id)
                DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding
                """;
        resilience.execute(ResilienceTarget.VECTOR, () -> {
            jdbc.update(sql, namespace, docId, content,
                    toJson(metadata), toVectorLiteral(vectorize(content)));
            return null;
        });
    }

    @Override
    public List<KnowledgeHit> search(String namespace, String query, int topK, double minScore) {
        String sql = """
                SELECT doc_id, content, 1 - (embedding <=> CAST(? AS VECTOR)) AS score
                FROM vector_doc
                WHERE namespace = ? AND 1 - (embedding <=> CAST(? AS VECTOR)) >= ?
                ORDER BY embedding <=> CAST(? AS VECTOR)
                LIMIT ?
                """;
        return resilience.execute(ResilienceTarget.VECTOR, () -> {
            String queryLiteral = toVectorLiteral(vectorize(query));
            return jdbc.query(sql, (rs, i) -> new KnowledgeHit(
                            rs.getString("doc_id"), rs.getString("content"), rs.getDouble("score")),
                    queryLiteral, namespace, queryLiteral, minScore, queryLiteral);
        });
    }

    private float[] vectorize(String text) {
        return new DeterministicVectorizer().vectorize(text);
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        metadata.forEach((key, value) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(key.replace("\"", "\\\"")).append("\":\"").append(String.valueOf(value).replace("\"", "\\\"")).append('"');
        });
        return sb.append('}').toString();
    }
}
