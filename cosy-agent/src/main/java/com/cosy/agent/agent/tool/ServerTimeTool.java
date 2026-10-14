package com.cosy.agent.agent.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 示例工具：获取服务器当前本地时间。
 */
@Component
public class ServerTimeTool implements AgentTool {

    @Override
    public String name() {
        return "get_server_time";
    }

    @Override
    public String description() {
        return "获取服务器当前本地时间，格式 yyyy-MM-dd HH:mm:ss，并返回时区。";
    }

    @Override
    public boolean retryable() {
        return true; // 只读查询，幂等可重试
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return Map.of(
                "time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                "zone", ZoneId.systemDefault().toString());
    }
}
