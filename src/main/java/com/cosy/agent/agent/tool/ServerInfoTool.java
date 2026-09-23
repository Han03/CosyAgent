package com.cosy.agent.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例工具：获取智能体服务运行信息。
 */
@Component
public class ServerInfoTool implements AgentTool {

    @Override
    public String name() {
        return "get_server_info";
    }

    @Override
    public String description() {
        return "获取 CosyAgent 服务运行信息（应用名、JVM 版本、可用处理器数）。";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return Map.of(
                "application", "cosy-agent",
                "javaVersion", System.getProperty("java.version"),
                "processors", Runtime.getRuntime().availableProcessors());
    }
}
