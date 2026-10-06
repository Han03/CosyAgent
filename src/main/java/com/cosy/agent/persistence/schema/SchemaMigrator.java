package com.cosy.agent.persistence.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * MySQL 表结构迁移器（v2 方案：原各 Jdbc/Mysql Store 的 initSchema 集中于此）。
 *
 * <p>幂等设计（MySQL 8 无 CREATE INDEX IF NOT EXISTS / ADD COLUMN IF NOT EXISTS）：
 * <ul>
 *   <li>建表：CREATE TABLE IF NOT EXISTS（含全列定义）</li>
 *   <li>补列：information_schema.COLUMNS 判存在后 ALTER ADD COLUMN</li>
 *   <li>补索引：information_schema.STATISTICS 判存在后 CREATE INDEX</li>
 * </ul>
 * 由 {@link com.cosy.agent.persistence.config.MybatisConfig} 装配，仅 persistence=mysql 时执行。
 * 连接池由 spring-jdbc 管理（JdbcTemplate），不再出现任何手写 JDBC 直连代码。</p>
 */
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
            log.info("MySQL schema 迁移完成");
        } catch (RuntimeException e) {
            throw new IllegalStateException("初始化 MySQL 表结构失败（persistence=mysql）: " + e.getMessage(), e);
        }
    }

    void migrate() {
        createTaskTables();
        createCapabilityTables();
        createModelRoutingTables();
        createLlmCallLogTable();
    }

    // ---- 任务/会话 ----

    private void createTaskTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task (
                    task_id       VARCHAR(64) PRIMARY KEY,
                    session_id    VARCHAR(64) NOT NULL,
                    user_id       VARCHAR(64) NOT NULL,
                    state         VARCHAR(32) NOT NULL,
                    input         TEXT,
                    output        TEXT,
                    iterations    INT DEFAULT 0,
                    cost_ms       BIGINT DEFAULT 0,
                    error_message TEXT,
                    created_at    TIMESTAMP DEFAULT now(),
                    updated_at    TIMESTAMP DEFAULT now(),
                    finished_at   TIMESTAMP,
                    pinned        TINYINT(1) DEFAULT 0,
                    title_override VARCHAR(256)
                )""");
        ensureColumn("agent_task", "pinned", "TINYINT(1) DEFAULT 0");
        ensureColumn("agent_task", "title_override", "VARCHAR(256)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_trace (
                    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id       VARCHAR(64) NOT NULL REFERENCES agent_task(task_id),
                    seq           INT NOT NULL,
                    role          VARCHAR(16) NOT NULL,
                    content       TEXT,
                    tool_name     VARCHAR(128),
                    tool_arguments TEXT,
                    created_at    TIMESTAMP DEFAULT now()
                )""");
        ensureIndex("agent_trace", "idx_agent_trace_task", "task_id, seq");
        ensureIndex("agent_task", "idx_agent_task_session", "session_id, updated_at");
    }

    // ---- 能力中心 ----

    private void createCapabilityTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS capability_provider (
                    provider_id   VARCHAR(64)  PRIMARY KEY,
                    app_name      VARCHAR(128) NOT NULL,
                    base_url      VARCHAR(512) NOT NULL,
                    auth_type     VARCHAR(32)  NOT NULL DEFAULT 'shared-secret',
                    auth_model    VARCHAR(16)  NOT NULL DEFAULT 'none',
                    auth_header_name VARCHAR(64),
                    auth_param_name  VARCHAR(64),
                    auth_value_encrypted VARCHAR(512),
                    source        VARCHAR(16)  NOT NULL DEFAULT 'external',
                    call_token    VARCHAR(128),
                    mode          VARCHAR(8)   NOT NULL,
                    status        VARCHAR(16)  NOT NULL DEFAULT 'UP',
                    last_beat_at  DATETIME(3)  NULL,
                    created_at    DATETIME(3)  NOT NULL
                ) DEFAULT CHARSET=utf8mb4""");
        ensureColumn("capability_provider", "auth_model", "VARCHAR(16) NOT NULL DEFAULT 'none'");
        ensureColumn("capability_provider", "auth_header_name", "VARCHAR(64)");
        ensureColumn("capability_provider", "auth_param_name", "VARCHAR(64)");
        ensureColumn("capability_provider", "auth_value_encrypted", "VARCHAR(512)");
        ensureColumn("capability_provider", "source", "VARCHAR(16) NOT NULL DEFAULT 'external'");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS capability (
                    capability_name   VARCHAR(128) NOT NULL,
                    provider_id       VARCHAR(64)  NOT NULL,
                    description       VARCHAR(1024),
                    parameters_schema TEXT,
                    endpoint_path     VARCHAR(256) NOT NULL,
                    endpoint_method   VARCHAR(8)   NOT NULL,
                    retryable         BOOLEAN      NOT NULL DEFAULT FALSE,
                    namespace         VARCHAR(64)  NOT NULL DEFAULT 'default',
                    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (capability_name, provider_id),
                    CONSTRAINT fk_cap_provider FOREIGN KEY (provider_id)
                      REFERENCES capability_provider (provider_id) ON DELETE CASCADE
                ) DEFAULT CHARSET=utf8mb4""");
        ensureColumn("capability", "enabled", "BOOLEAN NOT NULL DEFAULT TRUE");
    }

    // ---- 模型路由 ----

    private void createModelRoutingTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS model_platform (
                    platform_name    VARCHAR(64)  PRIMARY KEY,
                    base_url         VARCHAR(512) NOT NULL,
                    api_key          VARCHAR(512),
                    type             VARCHAR(32)  DEFAULT 'openai',
                    enabled          TINYINT      DEFAULT 1,
                    timeout_ms       INT          DEFAULT 60000,
                    completions_path VARCHAR(128) DEFAULT '/v1/chat/completions'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        ensureColumn("model_platform", "type", "VARCHAR(32) DEFAULT 'openai'");
        ensureColumn("model_platform", "enabled", "TINYINT DEFAULT 1");
        ensureColumn("model_platform", "timeout_ms", "INT DEFAULT 60000");
        ensureColumn("model_platform", "completions_path", "VARCHAR(128) DEFAULT '/v1/chat/completions'");
        jdbc.execute("ALTER TABLE model_platform MODIFY COLUMN api_key VARCHAR(512)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS model_spec (
                    platform_name  VARCHAR(64)   NOT NULL,
                    model_id       VARCHAR(128)  NOT NULL,
                    context_window INT           DEFAULT 0,
                    capabilities   VARCHAR(1024) DEFAULT '',
                    PRIMARY KEY (platform_name, model_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS model_route (
                    route_type  VARCHAR(64)  NOT NULL,
                    candidate   VARCHAR(256) NOT NULL,
                    seq         INT          NOT NULL,
                    PRIMARY KEY (route_type, candidate)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    // ---- LLM 调用记录 ----

    private void createLlmCallLogTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS llm_call_log (
                    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                    trace_id           VARCHAR(64)  NOT NULL,
                    session_id         VARCHAR(64)  NULL,
                    task_id            VARCHAR(64)  NULL,
                    iteration          INT          NOT NULL DEFAULT 0,
                    tool_name          VARCHAR(64)  NULL,
                    route_type         VARCHAR(32)  NOT NULL DEFAULT 'default',
                    model_choice       VARCHAR(128) NOT NULL,
                    candidate_chain    VARCHAR(512) NULL,
                    injected_tools     VARCHAR(512) NULL,
                    attempts           VARCHAR(512) NULL,
                    reasons            TEXT         NULL,
                    chosen_model       VARCHAR(128) NULL,
                    status             VARCHAR(16)  NOT NULL,
                    error_msg          VARCHAR(512) NULL,
                    decision_rationale VARCHAR(1024) NULL,
                    prompt_content     MEDIUMTEXT   NULL,
                    raw_prompt         MEDIUMTEXT   NULL,
                    response_content   MEDIUMTEXT   NULL,
                    prompt_tokens      INT          NULL,
                    completion_tokens  INT          NULL,
                    total_tokens       INT          NULL,
                    latency_ms         INT          NOT NULL DEFAULT 0,
                    started_at         DATETIME(3)  NOT NULL,
                    finished_at        DATETIME(3)  NOT NULL,
                    KEY idx_session (session_id, started_at),
                    KEY idx_model   (chosen_model, started_at),
                    KEY idx_status  (status, started_at),
                    KEY idx_trace   (trace_id)
                ) DEFAULT CHARSET=utf8mb4""");
        ensureColumn("llm_call_log", "injected_tools", "VARCHAR(512) NULL");
        ensureColumn("llm_call_log", "raw_prompt", "MEDIUMTEXT NULL");
    }

    // ---- 幂等辅助 ----

    /** 列不存在才 ALTER（information_schema 判断；并发/权限异常静默） */
    private void ensureColumn(String table, String column, String ddl) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?""",
                    Integer.class, table, column);
            if (count != null && count == 0) {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
            }
        } catch (RuntimeException ignored) {
            // 并发建列/权限等异常静默（后续读写可见列即恢复）
        }
    }

    /** 索引不存在才创建（information_schema.STATISTICS 判断） */
    private void ensureIndex(String table, String indexName, String columns) {
        try {
            List<Integer> counts = jdbc.query("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?""",
                    (rs, i) -> rs.getInt(1), table, indexName);
            if (counts.isEmpty() || counts.get(0) == 0) {
                jdbc.execute("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
            }
        } catch (RuntimeException ignored) {
            // 并发建索引等异常静默
        }
    }
}
