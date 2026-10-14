package com.cosy.capability;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CosyAgent 能力注册中心（P3.2 独立服务，端口 8282）。
 *
 * <p>职责边界：AP/CP 双模式注册、心跳/主动探测、候选链降级调用、四类认证注入、
 * submit-poll 三步协议、CP 持久化（MySQL cosy 库，与 agent 同表继承数据）。
 * agent 侧不再持有注册中心：动态工具注入改由 agent 的 CapabilitySync 周期拉取
 * {@code /api/agent/capability/tools} 清单并同步 ToolRegistry，执行时经
 * {@code /api/agent/capability/invoke} 委托回本服务完成调用。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CapabilityProperties.class)
@MapperScan("com.cosy.capability.persistence.mapper")
public class CosyCapabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CosyCapabilityApplication.class, args);
    }
}
