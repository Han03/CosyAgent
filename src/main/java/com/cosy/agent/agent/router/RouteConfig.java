package com.cosy.agent.agent.router;

import com.cosy.agent.config.ModelRoutingProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型路由配置模型：平台注册表 + 路由类型 → 有序候选链（降级顺序）。
 *
 * <p>来源统一：静态 YAML 基线（{@link ModelRoutingProperties}）或持久化 store 覆盖，
 * 二者映射为同一结构；管理 API 读写即操作本模型。</p>
 */
public record RouteConfig(
        boolean enabled,
        int maxCandidates,
        Map<String, ModelPlatform> platforms,
        Map<String, List<String>> routes) {

    /** 对话补全端点路径默认值（Spring AI OpenAiApi 约定） */
    public static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 平台注册项（name 为 Map key 冗余保留，便于序列化/校验） */
    public record ModelPlatform(String name, String baseUrl, String apiKey, String completionsPath) {

        public ModelPlatform {
            completionsPath = (completionsPath == null || completionsPath.isBlank())
                    ? DEFAULT_COMPLETIONS_PATH : completionsPath;
        }
    }

    /** 默认路由类型（auto 模式使用） */
    public static final String DEFAULT_ROUTE = "default";

    public static RouteConfig fromProperties(ModelRoutingProperties p) {
        Map<String, ModelPlatform> platforms = new LinkedHashMap<>();
        if (p.platforms() != null) {
            p.platforms().forEach((name, pf) -> platforms.put(name,
                    new ModelPlatform(name, pf.baseUrl(), pf.apiKey(), pf.completionsPath())));
        }
        return new RouteConfig(p.enabled(), p.maxCandidates(),
                platforms, p.routes() == null ? Map.of() : p.routes());
    }

    /** auto 模式的候选链（默认路由类型；缺失时回退空链） */
    public List<String> defaultCandidates() {
        return routes.getOrDefault(DEFAULT_ROUTE, List.of());
    }

    /**
     * 解析本次调用的候选链：
     * <ul>
     *   <li>modelChoice = auto/空 → 路由类型链（当前固定 default，预留 routeType 扩展）</li>
     *   <li>modelChoice = platform/model → 单候选链（锁定，不跨模型降级）</li>
     * </ul>
     *
     * @return 有序候选列表（按降级优先级）；指定模型平台未注册时抛 {@link IllegalArgumentException}
     */
    public List<String> resolveCandidates(String modelChoice) {
        if (modelChoice == null || modelChoice.isBlank() || "auto".equalsIgnoreCase(modelChoice.trim())) {
            return truncate(defaultCandidates());
        }
        String candidate = modelChoice.trim();
        String platform = candidate.contains("/") ? candidate.substring(0, candidate.indexOf('/')) : candidate;
        if (!platforms.containsKey(platform)) {
            throw new IllegalArgumentException("指定模型不可用（平台未注册）: " + candidate);
        }
        return List.of(candidate);
    }

    /** 全部路由链引用的候选模型去重（catalog 数据源；保持声明顺序） */
    public List<String> catalogModels() {
        Set<String> models = new LinkedHashSet<>();
        routes.forEach((type, candidates) -> candidates.forEach(c -> {
            if (platforms.containsKey(c.substring(0, c.indexOf('/')))) {
                models.add(c);
            }
        }));
        return new ArrayList<>(models);
    }

    public List<String> catalogRouteTypes() {
        return new ArrayList<>(routes.keySet());
    }

    private List<String> truncate(List<String> candidates) {
        int max = Math.max(1, maxCandidates);
        return candidates.size() > max ? new ArrayList<>(candidates.subList(0, max)) : candidates;
    }

    /** 当前配置中某候选对应的平台；未注册返回 empty */
    public Optional<ModelPlatform> platformFor(String candidate) {
        if (candidate == null || !candidate.contains("/")) {
            return Optional.empty();
        }
        return Optional.ofNullable(platforms.get(candidate.substring(0, candidate.indexOf('/'))));
    }
}
