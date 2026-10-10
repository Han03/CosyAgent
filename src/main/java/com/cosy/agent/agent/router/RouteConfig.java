package com.cosy.agent.agent.router;

import com.cosy.agent.config.ModelRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(RouteConfig.class);

    /** 对话补全端点路径默认值（1.x Spring AI OpenAiApi 约定；2.0 中并入 SDK baseUrl） */
    public static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 平台注册项（name 为 Map key 冗余保留，便于序列化/校验） */
    public record ModelPlatform(String name, String baseUrl, String apiKey, String completionsPath,
                                String type, boolean enabled, int timeoutMs, List<ModelSpec> models) {

        public ModelPlatform {
            completionsPath = (completionsPath == null || completionsPath.isBlank())
                    ? DEFAULT_COMPLETIONS_PATH : completionsPath;
            type = (type == null || type.isBlank()) ? DEFAULT_PLATFORM_TYPE : type;
            timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
            models = models == null ? List.of() : List.copyOf(models);
        }

        /** 兼容旧构造（未声明元数据时归一化默认值） */
        public ModelPlatform(String name, String baseUrl, String apiKey, String completionsPath) {
            this(name, baseUrl, apiKey, completionsPath, DEFAULT_PLATFORM_TYPE, true, DEFAULT_TIMEOUT_MS, List.of());
        }
    }

    /** 平台类型默认值（OpenAI 兼容协议） */
    public static final String DEFAULT_PLATFORM_TYPE = "openai";
    /** 单请求超时默认值（毫秒） */
    public static final int DEFAULT_TIMEOUT_MS = 60_000;

    /**
     * 平台下的模型规格（配置域元数据 + L2 画像字段）。
     *
     * @param modelId      模型标识（如 gpt-4o-mini）
     * @param contextWindow 上下文窗口（token）；0 = 未登记（L2 上下文匹配按可覆盖处理）
     * @param capabilities 能力标签（chat/tool/reasoning/fast/cheap…）
     * @param speed        响应速度档位 1-5（5=最快；默认 3）
     * @param reasoning    推理强度档位 1-5（5=最强；默认 3）
     * @param toolSupport  工具调用稳定度 1-5（默认 3）
     * @param priceTier    成本档位 1-5（5=最贵；默认 2）
     */
    public record ModelSpec(String modelId, int contextWindow, List<String> capabilities,
                            int speed, int reasoning, int toolSupport, int priceTier) {

        public ModelSpec {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            speed = speed <= 0 ? 3 : speed;
            reasoning = reasoning <= 0 ? 3 : reasoning;
            toolSupport = toolSupport <= 0 ? 3 : toolSupport;
            priceTier = priceTier <= 0 ? 2 : priceTier;
        }

        /** 兼容旧构造（未声明画像时归一化默认值） */
        public ModelSpec(String modelId, int contextWindow, List<String> capabilities) {
            this(modelId, contextWindow, capabilities, 3, 3, 3, 2);
        }

        /** 未登记画像时的中性画像（L2 打分的兜底值） */
        public static ModelSpec unknown(String modelId) {
            return new ModelSpec(modelId, 0, List.of("chat"));
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
     *   <li>modelChoice = auto/空 → 按路由类型取链（routeType 存在取之，
     *       否则回退 default 链；routeType 指定的类型不存在时回退 default 并告警）</li>
     *   <li>modelChoice = platform/model → 单候选链（锁定，不跨模型降级，忽略 routeType）</li>
     * </ul>
     *
     * @return 有序候选列表（按降级优先级）；指定模型平台未注册时抛 {@link IllegalArgumentException}
     */
    public List<String> resolveCandidates(String modelChoice, String routeType) {
        if (modelChoice == null || modelChoice.isBlank() || "auto".equalsIgnoreCase(modelChoice.trim())) {
            String type = routeType == null || routeType.isBlank() ? DEFAULT_ROUTE : routeType.trim();
            List<String> chain = routes.get(type);
            if (chain == null) {
                log.warn("路由类型不存在，回退 default 链: routeType={}", type);
                chain = defaultCandidates();
            }
            return truncate(chain);
        }
        String candidate = modelChoice.trim();
        String platform = candidate.contains("/") ? candidate.substring(0, candidate.indexOf('/')) : candidate;
        if (!platforms.containsKey(platform)) {
            throw new IllegalArgumentException("指定模型不可用（平台未注册）: " + candidate);
        }
        return List.of(candidate);
    }

    /** 全部路由链引用的候选模型去重（catalog 数据源；保持声明顺序） */
    /**
     * 客户端模型选择器数据源（可执行集合）：
     * 启用平台的已登记模型 + 路由规则引用的候选（去重并集）。
     * 规则引用优先收敛（updateRules 自动登记），此处并集兜底保证
     * 选择器恒包含"规则可执行"与"已登记"两类模型，与模型管理配置域一致。
     */
    public List<String> catalogModels() {
        Set<String> models = new LinkedHashSet<>();
        platforms.forEach((name, pf) -> {
            if (pf.enabled()) {
                pf.models().forEach(m -> models.add(name + "/" + m.modelId()));
            }
        });
        routes.forEach((type, candidates) -> candidates.forEach(c -> {
            if (c != null && c.contains("/")) {
                String platform = c.substring(0, c.indexOf('/'));
                RouteConfig.ModelPlatform pf = platforms.get(platform);
                if (pf != null && pf.enabled()) {
                    models.add(c);
                }
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
