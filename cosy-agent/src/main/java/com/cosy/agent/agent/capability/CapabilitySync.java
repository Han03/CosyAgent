package com.cosy.agent.agent.capability;

import com.cosy.agent.agent.tool.AgentTool;
import com.cosy.agent.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 能力清单同步器（P3.2）：周期拉取 capability 服务 {@code /tools} 清单，
 * 与本地 ToolRegistry diff 后注入/摘除 {@link CapabilityProxyTool}。
 *
 * <p>与旧版「注册中心持有 ToolRegistry 主动同步」相比：注入职责回归 agent，
 * 状态变化（注册/摘除/启停/探活/心跳摘除）由 capability 服务 listTools 过滤，
 * 经本同步器在下一轮周期自然收敛——模型下一轮对话即看到最新工具集。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.capability", name = "enabled", havingValue = "true")
public class CapabilitySync {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySync.class);

    private final CapabilityClient client;
    private final ToolRegistry toolRegistry;

    public CapabilitySync(CapabilityClient client, ToolRegistry toolRegistry) {
        this.client = client;
        this.toolRegistry = toolRegistry;
    }

    /** 初始延迟 3s（等注册中心/能力服务就绪），此后按 sync-interval 周期拉取（P4.1：周期兜底，MQ 事件即时触发） */
    @Scheduled(initialDelay = 3_000, fixedDelayString = "${cosy.agent.capability.sync-interval:10s}")
    public void sync() {
        syncNow();
    }

    /** 立即同步一次（MQ 能力变更事件触发 + 周期兜底共用） */
    public void syncNow() {
        try {
            List<Map<String, Object>> remote = client.tools().data();
            if (remote == null) {
                remote = List.of();
            }
            Set<String> remoteNames = remote.stream()
                    .map(m -> String.valueOf(m.get("name")))
                    .collect(Collectors.toSet());
            // 摘除：本地 capability proxy 已不在远端清单（下线/摘除/不可达）
            for (AgentTool tool : List.copyOf(toolRegistry.all())) {
                if (tool instanceof CapabilityProxyTool proxy
                        && !remoteNames.contains(proxy.capabilityName())) {
                    toolRegistry.unregister(proxy.capabilityName());
                    log.debug("能力工具摘除: {}", proxy.capabilityName());
                }
            }
            // 注入：远端新增能力（本地工具同名冲突跳过——本地工具优先）
            for (Map<String, Object> def : remote) {
                String name = String.valueOf(def.get("name"));
                if (toolRegistry.find(name).isPresent()) {
                    continue;
                }
                CapabilityDefinition definition = new CapabilityDefinition(
                        name,
                        String.valueOf(def.get("description")),
                        parametersOf(def.get("parameters")),
                        Boolean.TRUE.equals(def.get("retryable")));
                toolRegistry.register(new CapabilityProxyTool(definition, client));
                log.debug("能力工具注入: {}", name);
            }
        } catch (RuntimeException e) {
            // 注册中心暂不可达：保持上一轮工具集，下轮重试（不中断对话）
            log.warn("能力清单同步失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parametersOf(Object raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                params.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return params;
    }
}
