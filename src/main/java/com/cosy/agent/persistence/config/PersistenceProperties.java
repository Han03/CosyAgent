package com.cosy.agent.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 持久化总配置（prefix: cosy.agent.persistence，v2 方案收敛单一开关）。
 *
 * @param store 存储实现：memory（默认，无外部依赖，重启即失）| mysql（业务数据落 MySQL）
 * @param mysql MySQL 连接参数（store=mysql 时装配 DataSource + MyBatis-Plus；
 *              未安装 MySQL 时应用以 memory 实现运行不受影响）
 */
@ConfigurationProperties(prefix = "cosy.agent.persistence")
public record PersistenceProperties(
        @DefaultValue("memory") String store,
        @NestedConfigurationProperty Mysql mysql) {

    public static final String STORE_MEMORY = "memory";
    public static final String STORE_MYSQL = "mysql";

    public boolean isMysql() {
        return STORE_MYSQL.equalsIgnoreCase(store);
    }

    public Mysql mysql() {
        return mysql != null ? mysql : DEFAULT_MYSQL;
    }

    public static final Mysql DEFAULT_MYSQL =
            new Mysql("jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                    "cosy", "cosy");

    public record Mysql(
            @DefaultValue("jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai") String url,
            @DefaultValue("cosy") String username,
            @DefaultValue("cosy") String password) {
    }
}
