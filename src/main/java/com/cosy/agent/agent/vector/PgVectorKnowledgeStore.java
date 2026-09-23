package com.cosy.agent.agent.vector;

import com.cosy.agent.config.VectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PGVector 向量知识库（生产实现，cosy.agent.vector.store=pgvector 时装配）：
 * PostgreSQL + pgvector 扩展，表 vector_doc（namespace、doc_id、content、metadata、embedding vector(256)），
 * 余弦距离（&lt;=&gt;）检索 + 命名空间隔离 + TopK/阈值过滤。
 *
 * 依赖原生 JDBC（连接参数走 cosy.agent.vector.pg.*），不触发 Spring DataSource 自动配置，
 * 未安装 PostgreSQL 时应用默认 memory 模式不受影响。生产可替换为连接池（HikariCP）。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.vector", name = "store", havingValue = "pgvector")
public class PgVectorKnowledgeStore implements VectorKnowledgeStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeStore.class);
    private static final int DIM = 256;

    private final String url;
    private final String username;
    private final String password;

    public PgVectorKnowledgeStore(VectorProperties properties) {
        VectorProperties.Pg pg = properties.pg();
        this.url = pg.url();
        this.username = pg.username();
        this.password = pg.password();
        initSchema();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void initSchema() {
        try (Connection conn = open()) {
            conn.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");
            conn.createStatement().execute("""
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
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 PGVector 失败（检查 PostgreSQL 与 pgvector 扩展）：" + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(String namespace, String docId, String content, Map<String, Object> metadata) {
        String sql = """
                INSERT INTO vector_doc (namespace, doc_id, content, metadata, embedding)
                VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS VECTOR))
                ON CONFLICT (namespace, doc_id)
                DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding
                """;
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, docId);
            ps.setString(3, content);
            ps.setString(4, toJson(metadata));
            ps.setString(5, toVectorLiteral(vectorize(content)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("PGVector upsert 失败：" + e.getMessage(), e);
        }
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
        String queryLiteral = toVectorLiteral(vectorize(query));
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, queryLiteral);
            ps.setString(2, namespace);
            ps.setString(3, queryLiteral);
            ps.setDouble(4, minScore);
            ps.setString(5, queryLiteral);
            ps.setInt(6, Math.max(0, topK));
            List<KnowledgeHit> hits = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new KnowledgeHit(rs.getString("doc_id"), rs.getString("content"), rs.getDouble("score")));
                }
            }
            return hits;
        } catch (SQLException e) {
            throw new IllegalStateException("PGVector 检索失败：" + e.getMessage(), e);
        }
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

    @Override
    public void destroy() {
        // 无池化连接，无需释放；连接均 try-with-resources 关闭
    }
}
