package com.cosy.agent.agent.router;

import com.cosy.agent.common.exception.BizException;
import com.cosy.agent.common.enums.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型路由管理服务（管理 API 后端）：读配置（api-key 脱敏）、写配置
 * （api-key 留空保持原值，热更新立即生效）、catalog 组装（客户端模型选择器数据源）。
 */
@Service
public class ModelRoutingAdmin {

    private final ModelRouter router;
    private final ModelRoutingConfigStore store;

    public ModelRoutingAdmin(ModelRouter router, ModelRoutingConfigStore store) {
        this.router = router;
        this.store = store;
    }

    /** 当前配置的脱敏视图（api-key 不回传明文，仅标记是否已配置） */
    public Map<String, Object> getConfig() {
        RouteConfig cfg = router.currentConfig();
        Map<String, Object> platforms = new LinkedHashMap<>();
        cfg.platforms().forEach((name, pf) -> platforms.put(name, Map.of(
                "baseUrl", pf.baseUrl(),
                "apiKeyConfigured", pf.apiKey() != null && !pf.apiKey().isBlank(),
                "maskedApiKey", mask(pf.apiKey()))));
        return Map.of(
                "enabled", cfg.enabled(),
                "maxCandidates", cfg.maxCandidates(),
                "platforms", platforms,
                "routes", cfg.routes());
    }

    /** 全量更新配置：api-key 字段为空/缺失 = 保持原值；保存到 store 并热更新 Router */
    public void updateConfig(UpdateRequest request) {
        RouteConfig current = router.currentConfig();
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        if (request.platforms() != null) {
            request.platforms().forEach((name, pf) -> {
                RouteConfig.ModelPlatform existing = current.platforms().get(name);
                String apiKey = (pf.apiKey() == null || pf.apiKey().isBlank())
                        ? (existing != null ? existing.apiKey() : null)
                        : pf.apiKey();
                platforms.put(name, new RouteConfig.ModelPlatform(name, pf.baseUrl(), apiKey));
            });
        } else {
            platforms.putAll(current.platforms());
        }
        boolean enabled = request.enabled() != null ? request.enabled() : current.enabled();
        int max = request.maxCandidates() != null ? request.maxCandidates() : current.maxCandidates();
        Map<String, List<String>> routes = request.routes() != null ? request.routes() : current.routes();

        RouteConfig updated = new RouteConfig(enabled, max, platforms, routes);
        try {
            store.save(updated);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONFIG_SAVE_FAILED, "路由配置持久化失败: " + e.getMessage());
        }
        router.refresh(updated);
    }

    /** 客户端模型选择器数据源：auto + 可用模型 + 路由类型 */
    public Map<String, Object> catalog() {
        RouteConfig cfg = router.currentConfig();
        return Map.of(
                "auto", true,
                "models", cfg.catalogModels(),
                "routeTypes", cfg.catalogRouteTypes());
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        int len = apiKey.length();
        return len <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(len - 4);
    }

    /** 管理 API 更新请求体（api-key 空 = 保持原值） */
    public record UpdateRequest(
            Boolean enabled,
            Integer maxCandidates,
            Map<String, PlatformDto> platforms,
            Map<String, List<String>> routes) {

        public record PlatformDto(String baseUrl, String apiKey) {
        }
    }
}
