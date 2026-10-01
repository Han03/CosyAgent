package com.cosy.agent.agent.router;

import com.cosy.agent.common.exception.BizException;
import com.cosy.agent.common.enums.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型路由管理服务（平台控制台后端）：模型管理平台化——平台/模型/路由规则的
 * 细粒度 CRUD + 连通性测试 + catalog 组装。
 *
 * <p>平台（Provider）→ 模型（ModelSpec）→ 路由规则（Rule：类型 → 有序候选链）；
 * 每次变更统一走"构造新 {@link RouteConfig} → store.save → router.refresh"，
 * 保存即热更新、立即生效。api-key 只写不读：查询仅回显掩码，更新时空值=保持原值。</p>
 */
@Service
public class ModelRoutingAdmin {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingAdmin.class);

    private final ModelRouter router;
    private final ModelRoutingConfigStore store;

    public ModelRoutingAdmin(ModelRouter router, ModelRoutingConfigStore store) {
        this.router = router;
        this.store = store;
    }

    // ---- 兼容旧管理 API（全量读/写 + catalog，行为不变） ----

    /** 当前配置的脱敏视图（api-key 不回传明文，仅标记是否已配置） */
    public Map<String, Object> getConfig() {
        RouteConfig cfg = router.currentConfig();
        Map<String, Object> platforms = new LinkedHashMap<>();
        cfg.platforms().forEach((name, pf) -> platforms.put(name, Map.of(
                "baseUrl", pf.baseUrl(),
                "completionsPath", pf.completionsPath(),
                "type", pf.type(),
                "enabled", pf.enabled(),
                "timeoutMs", pf.timeoutMs(),
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
                platforms.put(name, new RouteConfig.ModelPlatform(name, pf.baseUrl(), apiKey,
                        pf.completionsPath(),
                        existing != null ? existing.type() : "openai",
                        existing != null && existing.enabled(),
                        existing != null ? existing.timeoutMs() : RouteConfig.DEFAULT_TIMEOUT_MS,
                        existing != null ? existing.models() : List.of()));
            });
        } else {
            platforms.putAll(current.platforms());
        }
        boolean enabled = request.enabled() != null ? request.enabled() : current.enabled();
        int max = request.maxCandidates() != null ? request.maxCandidates() : current.maxCandidates();
        Map<String, List<String>> routes = request.routes() != null ? request.routes() : current.routes();

        persist(new RouteConfig(enabled, max, platforms, routes));
    }

    /** 客户端模型选择器数据源：auto + 可用模型 + 路由类型 */
    public Map<String, Object> catalog() {
        RouteConfig cfg = router.currentConfig();
        return Map.of(
                "auto", true,
                "models", cfg.catalogModels(),
                "routeTypes", cfg.catalogRouteTypes());
    }

    // ---- 细粒度 CRUD（平台控制台） ----

    /** 平台列表（脱敏 + 模型数） */
    public List<Map<String, Object>> listProviders() {
        RouteConfig cfg = router.currentConfig();
        List<Map<String, Object>> result = new ArrayList<>();
        cfg.platforms().forEach((name, pf) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("baseUrl", pf.baseUrl());
            item.put("completionsPath", pf.completionsPath());
            item.put("type", pf.type());
            item.put("enabled", pf.enabled());
            item.put("timeoutMs", pf.timeoutMs());
            item.put("apiKeyConfigured", pf.apiKey() != null && !pf.apiKey().isBlank());
            item.put("maskedApiKey", mask(pf.apiKey()));
            item.put("modelCount", pf.models().size());
            result.add(item);
        });
        return result;
    }

    /** 平台详情（含模型规格完整元数据） */
    public Map<String, Object> providerDetail(String name) {
        RouteConfig.ModelPlatform pf = platform(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", pf.name());
        result.put("baseUrl", pf.baseUrl());
        result.put("completionsPath", pf.completionsPath());
        result.put("type", pf.type());
        result.put("enabled", pf.enabled());
        result.put("timeoutMs", pf.timeoutMs());
        result.put("apiKeyConfigured", pf.apiKey() != null && !pf.apiKey().isBlank());
        result.put("maskedApiKey", mask(pf.apiKey()));
        result.put("models", pf.models().stream().map(m -> Map.<String, Object>of(
                "modelId", m.modelId(),
                "contextWindow", m.contextWindow(),
                "capabilities", m.capabilities())).toList());
        return result;
    }

    /** 新增平台（name 必填、api-key 必填一次） */
    public void createProvider(String name, ProviderDto dto) {
        requireName(name);
        if (dto == null || dto.apiKey() == null || dto.apiKey().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新增平台必须提供 api-key");
        }
        RouteConfig cfg = router.currentConfig();
        if (cfg.platforms().containsKey(name)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "平台已存在: " + name);
        }
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>(cfg.platforms());
        platforms.put(name, new RouteConfig.ModelPlatform(
                name, requireBaseUrl(dto.baseUrl()), dto.apiKey(), dto.completionsPath(),
                dto.type(), dto.enabled() == null || dto.enabled(), dto.timeoutMs(), List.of()));
        persist(new RouteConfig(cfg.enabled(), cfg.maxCandidates(), platforms, cfg.routes()));
    }

    /** 更新平台：api-key 空 = 保持原值；其余字段覆盖（models 走独立模型 CRUD） */
    public void updateProvider(String name, ProviderDto dto) {
        RouteConfig.ModelPlatform existing = platform(name);
        if (dto == null) {
            return;
        }
        String apiKey = (dto.apiKey() == null || dto.apiKey().isBlank()) ? existing.apiKey() : dto.apiKey();
        RouteConfig cfg = router.currentConfig();
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>(cfg.platforms());
        platforms.put(name, new RouteConfig.ModelPlatform(
                name,
                dto.baseUrl() != null && !dto.baseUrl().isBlank() ? dto.baseUrl() : existing.baseUrl(),
                apiKey,
                dto.completionsPath() != null && !dto.completionsPath().isBlank()
                        ? dto.completionsPath() : existing.completionsPath(),
                dto.type() != null && !dto.type().isBlank() ? dto.type() : existing.type(),
                dto.enabled() != null ? dto.enabled() : existing.enabled(),
                dto.timeoutMs() != null ? dto.timeoutMs() : existing.timeoutMs(),
                existing.models()));
        persist(new RouteConfig(cfg.enabled(), cfg.maxCandidates(), platforms, cfg.routes()));
    }

    /** 删除平台：被路由规则引用时拒绝（保证候选链一致） */
    public void deleteProvider(String name) {
        RouteConfig cfg = router.currentConfig();
        boolean referenced = cfg.routes().values().stream()
                .flatMap(List::stream)
                .anyMatch(c -> c != null && c.startsWith(name + "/"));
        if (referenced) {
            throw new BizException(ErrorCode.PARAM_ERROR, "平台被路由规则引用，请先移除相关候选: " + name);
        }
        if (!cfg.platforms().containsKey(name)) {
            throw new BizException(ErrorCode.NOT_FOUND, "平台不存在: " + name);
        }
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>(cfg.platforms());
        platforms.remove(name);
        persist(new RouteConfig(cfg.enabled(), cfg.maxCandidates(), platforms, cfg.routes()));
    }

    /** 平台下新增模型规格 */
    public void addModel(String name, String modelId, ModelDto dto) {
        RouteConfig.ModelPlatform pf = platform(name);
        requireModelId(modelId);
        if (pf.models().stream().anyMatch(m -> m.modelId().equals(modelId))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模型已存在: " + modelId);
        }
        List<RouteConfig.ModelSpec> models = new ArrayList<>(pf.models());
        models.add(new RouteConfig.ModelSpec(modelId,
                dto != null && dto.contextWindow() != null ? dto.contextWindow() : 0,
                dto != null ? dto.capabilities() : List.of()));
        replaceModels(name, models);
    }

    /** 更新模型规格 */
    public void updateModel(String name, String modelId, ModelDto dto) {
        RouteConfig.ModelPlatform pf = platform(name);
        if (dto == null || pf.models().stream().noneMatch(m -> m.modelId().equals(modelId))) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在: " + modelId);
        }
        List<RouteConfig.ModelSpec> models = new ArrayList<>(pf.models());
        models.removeIf(m -> m.modelId().equals(modelId));
        models.add(new RouteConfig.ModelSpec(modelId,
                dto.contextWindow() != null ? dto.contextWindow() : 0,
                dto.capabilities() != null ? dto.capabilities() : List.of()));
        replaceModels(name, models);
    }

    /** 删除模型规格：被路由规则引用时拒绝（保证规则候选引用完整性） */
    public void deleteModel(String name, String modelId) {
        RouteConfig.ModelPlatform pf = platform(name);
        RouteConfig cfg = router.currentConfig();
        boolean referenced = cfg.routes().values().stream()
                .flatMap(List::stream)
                .anyMatch(c -> c != null && c.equals(name + "/" + modelId));
        if (referenced) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "模型被路由规则引用，请先从规则候选移除: " + modelId);
        }
        List<RouteConfig.ModelSpec> models = new ArrayList<>(pf.models());
        boolean removed = models.removeIf(m -> m.modelId().equals(modelId));
        if (!removed) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在: " + modelId);
        }
        replaceModels(name, models);
    }

    /** 路由规则整体读取（类型 → 有序候选链） */
    public Map<String, List<String>> getRules() {
        return new LinkedHashMap<>(router.currentConfig().routes());
    }

    /**
     * 路由规则整体更新（类型 → 有序候选链，顺序即降级顺序）。
     * 候选必须引用已注册平台；引用的模型未登记时自动补登记进该平台
     * 模型规格（contextWindow=0、capabilities=["chat"] 占位，可在模型管理中补元数据），
     * 保证规则候选与配置域恒一致。返回本次自动补登记的模型列表。
     */
    public Map<String, Object> updateRules(Map<String, List<String>> rules) {
        if (rules == null) {
            return Map.of();
        }
        RouteConfig cfg = router.currentConfig();
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>(cfg.platforms());
        Map<String, List<String>> cleaned = new LinkedHashMap<>();
        List<String> registeredMissing = new ArrayList<>();
        rules.forEach((type, candidates) -> {
            if (type == null || type.isBlank()) {
                return;
            }
            List<String> list = candidates == null ? List.of()
                    : candidates.stream().filter(c -> c != null && !c.isBlank()).toList();
            for (String c : list) {
                int idx = c.indexOf('/');
                if (idx <= 0 || idx == c.length() - 1) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "候选格式必须为 平台/模型: " + c);
                }
                String platform = c.substring(0, idx);
                String model = c.substring(idx + 1);
                RouteConfig.ModelPlatform pf = platforms.get(platform);
                if (pf == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "候选平台未注册: " + c);
                }
                // 规则引用未登记模型 → 自动补登记（引用完整性收敛）
                if (pf.models().stream().noneMatch(m -> m.modelId().equals(model))) {
                    List<RouteConfig.ModelSpec> models = new ArrayList<>(pf.models());
                    models.add(new RouteConfig.ModelSpec(model, 0, List.of("chat")));
                    pf = new RouteConfig.ModelPlatform(pf.name(), pf.baseUrl(), pf.apiKey(),
                            pf.completionsPath(), pf.type(), pf.enabled(), pf.timeoutMs(), models);
                    platforms.put(platform, pf);
                    registeredMissing.add(c);
                }
            }
            cleaned.put(type.trim(), list);
        });
        persist(new RouteConfig(cfg.enabled(), cfg.maxCandidates(), platforms, cleaned));
        return registeredMissing.isEmpty() ? Map.of() : Map.of("registeredMissing", registeredMissing);
    }

    /** 连通性测试：对指定平台（+模型）发最小请求，返回耗时或可读错误（key 不出库） */
    public Map<String, Object> testConnection(String providerId, String modelId, String prompt) {
        RouteConfig.ModelPlatform pf = platform(providerId);
        if (!pf.enabled()) {
            return Map.of("ok", false, "error", "平台已停用: " + providerId);
        }
        String candidate = providerId + "/" + (modelId == null || modelId.isBlank()
                ? (pf.models().isEmpty() ? "test" : pf.models().get(0).modelId()) : modelId);
        try {
            // 复用运行时同一平台注册表：保证测试链路与真实调用一致
            ChatModel model = router.registry().getOrCreate(pf, candidate);
            String modelName = candidate.substring(candidate.indexOf('/') + 1);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(modelName)
                    .temperature(0.1)
                    .build();
            long start = System.currentTimeMillis();
            ChatResponse resp = model.call(new Prompt(prompt == null || prompt.isBlank()
                    ? "ping" : prompt, options));
            long cost = System.currentTimeMillis() - start;
            String sample = Optional.ofNullable(resp.getResult()).map(r -> r.getOutput().getText())
                    .orElse("").trim();
            log.info("模型连通性测试成功: {} 耗时 {}ms", candidate, cost);
            return Map.of("ok", true, "durationMs", (int) cost, "sample", sample);
        } catch (RuntimeException e) {
            log.warn("模型连通性测试失败: {} -> {}", candidate, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ---- 内部 ----

    private void replaceModels(String name, List<RouteConfig.ModelSpec> models) {
        RouteConfig cfg = router.currentConfig();
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>(cfg.platforms());
        RouteConfig.ModelPlatform pf = platforms.get(name);
        platforms.put(name, new RouteConfig.ModelPlatform(pf.name(), pf.baseUrl(), pf.apiKey(),
                pf.completionsPath(), pf.type(), pf.enabled(), pf.timeoutMs(), models));
        persist(new RouteConfig(cfg.enabled(), cfg.maxCandidates(), platforms, cfg.routes()));
    }

    private void persist(RouteConfig updated) {
        try {
            store.save(updated);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONFIG_SAVE_FAILED, "路由配置持久化失败: " + e.getMessage());
        }
        router.refresh(updated);
    }

    private RouteConfig.ModelPlatform platform(String name) {
        requireName(name);
        RouteConfig.ModelPlatform pf = router.currentConfig().platforms().get(name);
        if (pf == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "平台不存在: " + name);
        }
        return pf;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "平台 name 必填");
        }
    }

    private static void requireModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "modelId 必填");
        }
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "baseUrl 必填");
        }
        return baseUrl;
    }

    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        int len = apiKey.length();
        return len <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(len - 4);
    }

    // ---- 请求体（细粒度 CRUD） ----

    public record ProviderDto(String baseUrl, String apiKey, String completionsPath,
                              String type, Boolean enabled, Integer timeoutMs) {
    }

    public record ModelDto(Integer contextWindow, List<String> capabilities) {
    }

    /** 连通性测试请求体 */
    public record TestRequest(String providerId, String modelId, String prompt) {
    }

    /** 兼容旧管理 API 更新请求体（api-key 空 = 保持原值） */
    public record UpdateRequest(
            Boolean enabled,
            Integer maxCandidates,
            Map<String, PlatformDto> platforms,
            Map<String, List<String>> routes) {

        public record PlatformDto(String baseUrl, String apiKey, String completionsPath) {
        }
    }
}
