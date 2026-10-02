package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 大模型调用记录配置项（prefix: cosy.agent.llmlog）。
 *
 * @param enabled       总开关：false 时 CallRecorder 不装配，模型调用无记录（行为与现状一致）
 * @param contentMode   内容记录策略：plain（默认，开发阶段完整明文）| truncated（截断 contentChars）| none（不记内容）
 * @param contentChars  truncated 模式截断长度
 * @param queueCapacity 异步队列容量（满则丢弃并告警，保护内存）
 * @param flushIntervalMs 批量落库间隔（毫秒）
 * @param mysql         连接参数（与路由/能力 store 同库同源，独立段便于覆写）
 */
@ConfigurationProperties(prefix = "cosy.agent.llmlog")
public record LlmCallLogProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("plain") String contentMode,
        @DefaultValue("2000") int contentChars,
        @DefaultValue("10000") int queueCapacity,
        @DefaultValue("5000") long flushIntervalMs,
        @NestedConfigurationProperty Mysql mysql) {

    public static final String MODE_PLAIN = "plain";
    public static final String MODE_TRUNCATED = "truncated";
    public static final String MODE_NONE = "none";

    public record Mysql(
            @DefaultValue("jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai") String url,
            @DefaultValue("cosy") String username,
            @DefaultValue("cosy") String password) {
    }

    /** 按内容策略处理文本：plain 原样 / truncated 截断 / none 丢弃 */
    public String applyContent(String text) {
        if (text == null || MODE_NONE.equalsIgnoreCase(contentMode)) {
            return null;
        }
        if (MODE_TRUNCATED.equalsIgnoreCase(contentMode)
                && text.length() > contentChars) {
            return text.substring(0, contentChars) + "…";
        }
        return text;
    }
}
