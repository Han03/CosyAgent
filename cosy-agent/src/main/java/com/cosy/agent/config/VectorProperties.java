package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 知识检索参数（agent 侧，cosy.agent.vector.*）：
 * agent 的 ReAct 编排在系统提示注入知识命中时使用本配置；向量存储/切分/连接
 * 实现在独立服务 cosy-knowledge（同一前缀，各自进程内独立解析，互不影响）。
 *
 * @param namespace 默认命名空间（业务域/租户隔离）
 * @param topK      检索返回最大命中数
 * @param minScore  最小相似度阈值
 */
@ConfigurationProperties(prefix = "cosy.agent.vector")
public record VectorProperties(
        @DefaultValue("default") String namespace,
        @DefaultValue("5") int topK,
        @DefaultValue("0.15") double minScore) {
}
