package com.cosy.agent;

import com.cosy.agent.agent.capability.CapabilityProperties;
import com.cosy.agent.config.AgentProperties;
import com.cosy.agent.config.ModelRoutingProperties;
import com.cosy.agent.config.TaskProperties;
import com.cosy.agent.config.VectorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CosyAgent 企业级智能体系统启动类。
 *
 * <p>Step 1：承载基础框架（分层骨架 + 通用能力 + 工具注册表），
 * 后续能力按 docs/CosyAgent设计方案.md 的分步计划接入。
 * {@code @EnableScheduling}：能力注册中心心跳摘除 / CP 主动探测 的调度器。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AgentProperties.class, VectorProperties.class, TaskProperties.class,
        ModelRoutingProperties.class, CapabilityProperties.class})
public class CosyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CosyAgentApplication.class, args);
    }
}
