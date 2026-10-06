package com.cosy.agent.agent.capability;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.agent.router.ApiKeyCipher;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.common.enums.ErrorCode;
import com.cosy.agent.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 能力注册中心（AP/CP 双模式，参考 Nacos）。
 *
 * <p><b>AP（临时能力）</b>：注册即成功（内存权威）；心跳 5s 续约、15s 未续约标记 SUSPECT、
 * 30s 未续约摘除（与 Nacos 临时实例节奏一致）；调用失败快速标 DOWN 补偿最终一致。
 *
 * <p><b>CP（持久能力）</b>：注册需落库确认（store=mysql）；不因心跳丢失摘除，
 * 仅显式注销/管理移除；主动探测 /healthz，失败标 DOWN（resolve 跳过）但不摘除。
 *
 * <p>工具联动：能力注册/摘除时同步 {@link ToolRegistry}（每能力一个
 * {@link CapabilityProxyTool}），LLM 下一轮调用即可见/不可见，无需重启。
 * 远程调用套 {@link ResilienceTarget#TOOL} 容错；同能力多提供者按 UP 优先候选链降级。</p>
 */
@Service
@ConditionalOnProperty(prefix = "cosy.agent.capability", name = "enabled", havingValue = "true")
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    /** 提供者注册表（providerId → 提供者，AtomicReference 支持 CAS 状态迁移） */
    private final Map<String, AtomicReference<CapabilityProvider>> providers = new ConcurrentHashMap<>();
    /** 能力表（能力全名 → (providerId → 能力定义)），LinkedHashMap 保注册序 */
    private final Map<String, Map<String, Capability>> capabilities = new ConcurrentHashMap<>();

    private final ToolRegistry toolRegistry;
    private final CapabilityStore store;
    private final CapabilityProperties properties;
    private final ResilienceSupport resilience;
    private final RestClient restClient;
    private final ApiKeyCipher apiKeyCipher;
    private final CapabilityHealthProbe healthProbe;

    /** 提交/轮询响应的 JSON 解析（Jackson 无状态、线程安全） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 一个"可调用的能力单元"：提供者 × 能力定义（候选链元素） */
    public record ActiveCapability(CapabilityProvider provider, Capability capability) {
    }

    /** 注册结果：instanceId（providerId）+ 能力全名 + callToken（仅本次返回） */
    public record RegisterResult(String providerId, List<String> capabilityNames, String callToken) {
    }

    public CapabilityRegistry(ToolRegistry toolRegistry, CapabilityStore store,
                              CapabilityProperties properties, ResilienceSupport resilience,
                              RestClient.Builder restClientBuilder, ApiKeyCipher apiKeyCipher,
                              CapabilityHealthProbe healthProbe) {
        this.toolRegistry = toolRegistry;
        this.store = store;
        this.properties = properties;
        this.resilience = resilience;
        this.restClient = restClientBuilder.build();
        this.apiKeyCipher = apiKeyCipher;
        this.healthProbe = healthProbe;
        loadPersisted();
    }

    /** 启动恢复：store 中的 CP 提供者与能力装载进内存权威表 */
    private void loadPersisted() {
        List<CapabilityProvider> loaded = store.loadProviders();
        Map<String, List<Capability>> loadedCaps = store.loadCapabilities();
        log.info("启动恢复: providers={}, loadedCaps keys={}", loaded.size(), loadedCaps.keySet());
        for (CapabilityProvider p : loaded) {
            if (!p.isCp()) {
                continue;
            }
            providers.put(p.providerId(), new AtomicReference<>(p));
            for (Capability cap : loadedCaps.getOrDefault(p.providerId(), List.of())) {
                capabilities.computeIfAbsent(cap.fullName(), k -> new LinkedHashMap<>())
                        .put(p.providerId(), cap);
            }
        }
        if (!loaded.isEmpty()) {
            log.info("能力注册中心启动恢复: cp-providers={}, capabilities={}", loaded.size(), capabilities.size());
            refreshTools();
        }
    }

    /** 注册提供者及其能力。AP 即成功（内存）；CP 落库确认后返回。 */
    public RegisterResult register(RegisterRequest request) {
        String mode = normalizeMode(request.mode());
        String authModel = normalizeAuthModel(request.authModel());
        String providerId = UUID.randomUUID().toString().replace("-", "");
        String callToken = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        CapabilityProvider provider = new CapabilityProvider(
                providerId, request.appName(), request.baseUrl(), "shared-secret",
                authModel, request.authHeaderName(), request.authParamName(),
                apiKeyCipher.encrypt(request.authValue()),
                request.source(), mode, CapabilityStatus.UP, now, callToken, now);

        List<Capability> caps = buildCapabilities(request);
        for (Capability cap : caps) {
            capabilities.computeIfAbsent(cap.fullName(), k -> new LinkedHashMap<>()).put(providerId, cap);
        }
        providers.put(providerId, new AtomicReference<>(provider));

        if (provider.isCp()) {
            store.save(provider, caps); // CP：落库确认（失败即注册失败，强一致）
        }
        refreshTools();
        log.info("能力注册成功: provider={}, mode={}, capabilities={}", providerId, mode, caps.size());
        return new RegisterResult(providerId, caps.stream().map(Capability::fullName).toList(), callToken);
    }

    /**
     * 更新提供者（管理端，保留 providerId/callToken/createdAt/status）：
     * 认证字段全量覆盖（authValue 为空保持原密钥），能力定义全量替换。
     * CP 落库（先删后插，避免旧能力行残留）；返回 providerId。
     */
    public String updateProvider(String providerId, RegisterRequest request) {
        AtomicReference<CapabilityProvider> ref = providers.get(providerId);
        if (ref == null) {
            throw new BizException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND, providerId);
        }
        if (request.appName() == null || request.appName().isBlank()
                || request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new BizException(ErrorCode.CAPABILITY_INVALID_MODE, "appName 与 baseUrl 必填");
        }
        CapabilityProvider old = ref.get();
        String authModel = normalizeAuthModel(request.authModel());
        String encrypted = (request.authValue() == null || request.authValue().isBlank())
                ? old.authValueEncrypted()
                : apiKeyCipher.encrypt(request.authValue());
        CapabilityProvider updated = new CapabilityProvider(
                providerId, request.appName(), request.baseUrl(), "shared-secret",
                authModel, request.authHeaderName(), request.authParamName(), encrypted,
                "console", old.mode(), old.status(), old.lastBeatAt(), old.callToken(), old.createdAt());
        List<Capability> caps = buildCapabilities(request);
        // 能力全量替换：先摘除该提供者旧能力（含空名清理）
        capabilities.forEach((fullName, byProvider) -> byProvider.remove(providerId));
        capabilities.entrySet().removeIf(e -> e.getValue().isEmpty());
        for (Capability cap : caps) {
            capabilities.computeIfAbsent(cap.fullName(), k -> new LinkedHashMap<>()).put(providerId, cap);
        }
        ref.set(updated);
        if (updated.isCp()) {
            store.deleteProvider(providerId); // 先删旧行（provider 级联能力），再落新数据
            store.save(updated, caps);
        }
        refreshTools();
        log.info("能力提供者更新: provider={}, capabilities={}", providerId, caps.size());
        return providerId;
    }

    /** 校验并构造能力全量（工具名与本地工具冲突即拒绝；注册/更新共用） */
    private List<Capability> buildCapabilities(RegisterRequest request) {
        if (request.capabilities() == null || request.capabilities().isEmpty()) {
            throw new BizException(ErrorCode.CAPABILITY_INVALID_MODE, "capabilities 至少一项");
        }
        List<Capability> caps = new ArrayList<>();
        for (Capability c : request.capabilities()) {
            String namespace = c.namespace() == null || c.namespace().isBlank()
                    ? (request.namespace() == null || request.namespace().isBlank()
                        ? properties.namespace() : request.namespace())
                    : c.namespace();
            Capability full = new Capability(c.name(), c.description(), c.parameters(),
                    c.endpointPath(), c.endpointMethod(), c.retryable(), namespace,
                    c.endpointMode(), c.statusPath(), c.resultPath(),
                    c.pollIntervalMs(), c.pollTimeoutMs(), c.enabled());
            String fullName = full.fullName();
            // 工具名全局唯一：仅与"本地工具"冲突拒绝（能力 proxy 已存在 = 同能力多提供者，放行追加实例）
            boolean localConflict = toolRegistry.find(fullName)
                    .map(t -> !(t instanceof CapabilityProxyTool)).orElse(false);
            if (localConflict) {
                throw new BizException(ErrorCode.CAPABILITY_NAME_CONFLICT, fullName);
            }
            caps.add(full);
        }
        return caps;
    }

    /** AP 心跳续约（CP 也可续约仅更新 lastBeatAt，不参与摘除判定） */
    public void heartbeat(String providerId) {
        AtomicReference<CapabilityProvider> ref = providers.get(providerId);
        if (ref == null) {
            throw new BizException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND, providerId);
        }
        Instant now = Instant.now();
        ref.updateAndGet(p -> p.status() == CapabilityStatus.DOWN
                ? new CapabilityProvider(p.providerId(), p.appName(), p.baseUrl(), p.authType(),
                        p.authModel(), p.authHeaderName(), p.authParamName(), p.authValueEncrypted(),
                        p.source(), p.mode(), CapabilityStatus.UP, now, p.callToken(), p.createdAt())
                : new CapabilityProvider(p.providerId(), p.appName(), p.baseUrl(), p.authType(),
                        p.authModel(), p.authHeaderName(), p.authParamName(), p.authValueEncrypted(),
                        p.source(), p.mode(), p.status(), now, p.callToken(), p.createdAt()));
    }

    /** 注销：AP/CP 均可显式注销；CP 必须显式注销才移除 */
    public void deregister(String providerId) {
        providers.remove(providerId);
        boolean removedAny = capabilities.values().removeIf(m -> {
            m.remove(providerId);
            return m.isEmpty();
        });
        store.deleteProvider(providerId);
        if (removedAny) {
            refreshTools();
        }
        log.info("能力注销: provider={}", providerId);
    }

    /**
     * 寻址候选链：某能力的全部实例，UP 优先（其余按注册序）。
     * 空链抛 CAPABILITY_NOT_FOUND（模型可能幻觉到已摘除能力，走工具未找到语义）。
     */
    public List<ActiveCapability> resolve(String capabilityName) {
        Map<String, Capability> byProvider = capabilities.get(capabilityName);
        if (byProvider == null || byProvider.isEmpty()) {
            throw new BizException(ErrorCode.CAPABILITY_NOT_FOUND, capabilityName);
        }
        List<ActiveCapability> chain = new ArrayList<>();
        List<ActiveCapability> degraded = new ArrayList<>();
        for (Map.Entry<String, Capability> e : byProvider.entrySet()) {
            AtomicReference<CapabilityProvider> ref = providers.get(e.getKey());
            if (ref == null) {
                continue;
            }
            CapabilityProvider p = ref.get();
            if (!e.getValue().enabled()) {
                continue; // 手动下线：不参与寻址（proxy 已摘除，模型不可见）
            }
            ActiveCapability ac = new ActiveCapability(p, e.getValue());
            if (p.status().callable()) {
                chain.add(ac);
            } else {
                degraded.add(ac);
            }
        }
        chain.addAll(degraded); // UP 优先 + 降级在后（保注册序）
        return chain;
    }

    /**
     * 调用远程能力（CapabilityProxyTool 委托）：候选链依次调用，
     * 失败标 DOWN → 切下一候选；全部失败抛 CAPABILITY_ALL_FAILED。
     * 按能力 endpointMode 分流：sync 单次 HTTP；submit-poll 提交→轮询→取结果。
     */
    public Object call(String capabilityName, Map<String, Object> args) {
        List<ActiveCapability> chain = resolve(capabilityName);
        List<String> failures = new ArrayList<>();
        for (ActiveCapability ac : chain) {
            try {
                return resilience.execute(ResilienceTarget.TOOL,
                        () -> invoke(ac.provider(), ac.capability(), args));
            } catch (RuntimeException e) {
                markDown(capabilityName, ac.provider().providerId());
                failures.add(ac.provider().providerId() + ":" + rootMessage(e));
                log.warn("能力调用失败，切换下一候选: capability={}, provider={}, cause={}",
                        capabilityName, ac.provider().providerId(), rootMessage(e));
            }
        }
        throw new BizException(ErrorCode.CAPABILITY_ALL_FAILED,
                capabilityName + " 全部候选失败: " + failures);
    }

    /** 按能力模式分流：submit-poll 走长任务三步协议，否则单次 HTTP */
    private Object invoke(CapabilityProvider provider, Capability capability, Map<String, Object> args) {
        return capability.isSubmitPoll()
                ? invokeSubmitPoll(provider, capability, args)
                : invokeSync(provider, capability, args);
    }

    /** 调用失败标记：AP 立即 DOWN（候选链跳过），CP 标 DOWN 保留（探测恢复） */
    public void markDown(String capabilityName, String providerId) {
        AtomicReference<CapabilityProvider> ref = providers.get(providerId);
        if (ref != null && ref.get().status().callable()) {
            ref.set(withStatus(ref.get(), CapabilityStatus.DOWN));
            log.warn("能力实例标记 DOWN: capability={}, provider={}", capabilityName, providerId);
            refreshTools(); // 提供者不可达 → 该能力从提示词摘除（模型下一轮不再看到）
        }
    }

    /** 每心跳周期扫描 AP 实例：suspectAfter → SUSPECT；expireAfter → 摘除 */
    @Scheduled(fixedDelayString = "${cosy.agent.capability.heartbeat.interval:5s}")
    public void sweepHeartbeat() {
        Duration suspectAfter = properties.heartbeat().suspectAfter();
        Duration expireAfter = properties.heartbeat().expireAfter();
        Instant now = Instant.now();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, AtomicReference<CapabilityProvider>> e : providers.entrySet()) {
            CapabilityProvider p = e.getValue().get();
            if (!p.isAp()) {
                continue;
            }
            Duration idle = p.lastBeatAt() == null ? expireAfter.plus(Duration.ofSeconds(1))
                    : Duration.between(p.lastBeatAt(), now);
            if (idle.compareTo(expireAfter) >= 0) {
                expired.add(e.getKey());
            } else if (idle.compareTo(suspectAfter) >= 0 && p.status() == CapabilityStatus.UP) {
                e.getValue().set(withStatus(p, CapabilityStatus.SUSPECT));
            }
        }
        for (String pid : expired) {
            log.warn("AP 心跳超时摘除: provider={}", pid);
            deregister(pid);
        }
    }

    /** CP 主动健康检查：周期探测 {baseUrl}/healthz，失败标 DOWN（不摘除），成功恢复 UP。
     *  initialDelay=1s：启动即探活一次，避免服务未上线时前 60s 空窗期误注入。 */
    @Scheduled(initialDelay = 1_000, fixedDelayString = "${cosy.agent.capability.probe.interval:60s}")
    public void probeCpProviders() {
        if (!properties.probe().enabled()) {
            return;
        }
        for (AtomicReference<CapabilityProvider> ref : providers.values()) {
            CapabilityProvider p = ref.get();
            if (!p.isCp()) {
                continue;
            }
            boolean alive;
            try {
                String url = p.baseUrl().replaceAll("/+$", "") + "/healthz";
                restClient.get().uri(url).retrieve().toBodilessEntity();
                alive = true;
            } catch (RuntimeException e) {
                alive = false;
            }
            CapabilityStatus target = alive ? CapabilityStatus.UP : CapabilityStatus.DOWN;
            if (p.status() != target) {
                ref.set(withStatus(p, target));
                log.info("CP 探测: provider={}, status={}", p.providerId(), target);
                refreshTools(); // 状态变化立即同步工具集（不可达能力从提示词摘除/恢复）
            }
        }
    }

    /** 全部提供者（管理目录） */
    public List<CapabilityProvider> allProviders() {
        return providers.values().stream().map(AtomicReference::get).toList();
    }

    /** 提供者的能力定义列表 */
    public List<Capability> capabilitiesOf(String providerId) {
        List<Capability> list = new ArrayList<>();
        for (Map<String, Capability> m : capabilities.values()) {
            Capability c = m.get(providerId);
            if (c != null) {
                list.add(c);
            }
        }
        return list;
    }

    /** 全部能力名（工具同步依据） */
    public Set<String> capabilityNames() {
        return Set.copyOf(capabilities.keySet());
    }

    // ---- CapabilityProxyTool 元数据访问（取候选链首实例的定义；无实例返回默认） ----

    public String describe(String capabilityName) {
        Map<String, Capability> byProvider = capabilities.get(capabilityName);
        return byProvider == null || byProvider.isEmpty()
                ? "远程能力：" + capabilityName
                : byProvider.values().iterator().next().description();
    }

    public Map<String, String> parametersOf(String capabilityName) {
        Map<String, Capability> byProvider = capabilities.get(capabilityName);
        return byProvider == null || byProvider.isEmpty()
                ? Map.of()
                : byProvider.values().iterator().next().parameters();
    }

    public boolean retryableOf(String capabilityName) {
        Map<String, Capability> byProvider = capabilities.get(capabilityName);
        return byProvider != null && !byProvider.isEmpty()
                && byProvider.values().iterator().next().retryable();
    }

    /** 调用模式（sync / submit-poll），健康目录展示用；无能力时返回 sync */
    public String modeOf(String capabilityName) {
        Map<String, Capability> byProvider = capabilities.get(capabilityName);
        return byProvider == null || byProvider.isEmpty()
                ? Capability.MODE_SYNC
                : byProvider.values().iterator().next().endpointMode();
    }

    /** 寻址但不抛异常（健康目录/代理元数据用）：无能力时返回空链 */
    public List<ActiveCapability> resolveQuietly(String capabilityName) {
        try {
            return resolve(capabilityName);
        } catch (BizException e) {
            return List.of();
        }
    }

    /**
     * 工具同步：能力表与 ToolRegistry 对齐——仅注入「已启用且提供者 TCP 可达」的能力 proxy，
     * 已摘除/下线/不可达的 proxy 移除。注册/摘除/启停/探活后调用；模型下一轮即看到最新工具集。
     * 注：可达性判据用 TCP 连通（healthProbe），与业务健康 status（/healthz）解耦——后者仅用于调用降级。
     */
    public synchronized void refreshTools() {
        Set<String> active = new java.util.HashSet<>();
        for (Map.Entry<String, Map<String, Capability>> e : capabilities.entrySet()) {
            String name = e.getKey();
            for (Map.Entry<String, Capability> inst : e.getValue().entrySet()) {
                AtomicReference<CapabilityProvider> ref = providers.get(inst.getKey());
                CapabilityProvider p = ref == null ? null : ref.get();
                if (p != null && inst.getValue().enabled() && healthProbe.isReachable(p)) {
                    active.add(name); // 任一实例启用且可达 → 注入
                    break;
                }
            }
        }
        // 移除已无启用实例 / 已摘除的 proxy 工具
        for (com.cosy.agent.agent.tool.AgentTool tool : List.copyOf(toolRegistry.all())) {
            if (tool instanceof CapabilityProxyTool proxy && !active.contains(proxy.capabilityName())) {
                toolRegistry.unregister(proxy.capabilityName());
            }
        }
        // 注入新能力的 proxy（本地工具同名冲突在注册时已拦截）
        for (String name : active) {
            if (toolRegistry.find(name).isEmpty()) {
                toolRegistry.register(new CapabilityProxyTool(name, this));
                log.debug("能力工具注入: {}", name);
            }
        }
    }

    /**
     * 能力启停（手动上线/下线）：作用于该能力全部提供者实例。
     * 下线后 proxy 工具摘除（下一轮模型不再看到）、寻址空链（调用返回未找到）。
     * CP 实例落库（enabled 随能力行持久化），AP 仅内存。返回受影响实例数。
     */
    public int setEnabled(String capabilityName, boolean enabled) {
        Map<String, Capability> byProvider = capabilities.get(capabilityName);
        if (byProvider == null || byProvider.isEmpty()) {
            throw new BizException(ErrorCode.CAPABILITY_NOT_FOUND, capabilityName);
        }
        int affected = 0;
        for (Map.Entry<String, Capability> e : new ArrayList<>(byProvider.entrySet())) {
            Capability c = e.getValue();
            if (c.enabled() == enabled) {
                continue;
            }
            Capability updated = new Capability(c.name(), c.description(), c.parameters(),
                    c.endpointPath(), c.endpointMethod(), c.retryable(), c.namespace(),
                    c.endpointMode(), c.statusPath(), c.resultPath(),
                    c.pollIntervalMs(), c.pollTimeoutMs(), enabled);
            byProvider.put(e.getKey(), updated);
            AtomicReference<CapabilityProvider> ref = providers.get(e.getKey());
            if (ref != null && ref.get().isCp()) {
                store.updateCapabilityEnabled(ref.get().providerId(), updated);
            }
            affected++;
        }
        refreshTools();
        log.info("能力启停: capability={}, enabled={}, instances={}", capabilityName, enabled, affected);
        return affected;
    }

    /** sync：单次 HTTP 调用（外层 call() 已对每个候选套 ResilienceTarget.TOOL 容错）。
     * 支持 RESTful 路径模板：endpointPath 中含 {arg} 时从参数中取值替换（如
     * /api/books/scripts/{id}），其余参数走 query（GET）或 JSON body（POST）。 */
    private Object invokeSync(CapabilityProvider provider, Capability capability, Map<String, Object> args) {
        Map<String, Object> params = args == null ? Map.of() : new LinkedHashMap<>(args);
        String path = capability.endpointPath();
        if (path.contains("{")) {
            for (String k : params.keySet().toArray(new String[0])) {
                String token = "{" + k + "}";
                if (path.contains(token)) {
                    path = path.replace(token, String.valueOf(params.get(k)));
                    params.remove(k);
                }
            }
        }
        String url = provider.baseUrl().replaceAll("/+$", "") + path;
        boolean isGet = "GET".equalsIgnoreCase(capability.endpointMethod());
        // query 认证参数优先拼接（模型生成的业务参数不覆盖认证参数名）
        url = url + authQuery(provider, url);
        if (isGet && !params.isEmpty()) {
            // 参数间 & 分隔 + 键值 URL 编码（模型生成的参数可能是 float/bool 等原始类型）
            StringBuilder q = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!first) {
                    q.append('&');
                }
                first = false;
                q.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), java.nio.charset.StandardCharsets.UTF_8));
            }
            url = url + (url.contains("?") ? "&" : "?") + q;
        }
        String body;
        if (isGet) {
            RestClient.RequestHeadersSpec<?> get = restClient.get().uri(url);
            applyAuthHeaders(get, provider);
            body = get.header("X-Capability-Call-Token", provider.callToken())
                    .retrieve().body(String.class);
        } else {
            RestClient.RequestBodySpec post = restClient.post().uri(url);
            applyAuthHeaders(post, provider);
            body = post.header("X-Capability-Call-Token", provider.callToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(params)
                    .retrieve().body(String.class);
        }
        return body == null ? Map.of() : body;
    }

    /**
     * submit-poll 三步协议：提交长任务 → 轮询状态至终态 → 取完整结果。
     * 全程仍在候选链容错内：提交/轮询失败由外层标 DOWN 切下一候选。
     */
    private Object invokeSubmitPoll(CapabilityProvider provider, Capability capability, Map<String, Object> args) {
        if (capability.statusPath() == null || capability.statusPath().isBlank()
                || capability.resultPath() == null || capability.resultPath().isBlank()) {
            throw new IllegalStateException("submit-poll 能力必须配置 statusPath/resultPath: " + capability.fullName());
        }
        String base = provider.baseUrl().replaceAll("/+$", "");
        // 1) 提交
        RestClient.RequestBodySpec submit = restClient.post().uri(base + capability.endpointPath() + authQuery(provider, base + capability.endpointPath()));
        applyAuthHeaders(submit, provider);
        String submitResp = submit.header("X-Capability-Call-Token", provider.callToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(args == null ? Map.of() : args)
                .retrieve().body(String.class);
        String taskId = extractTaskId(submitResp);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("提交响应缺少 task_id: " + submitResp);
        }
        String statusPath = capability.statusPath().replace("{id}", taskId);
        String resultPath = capability.resultPath().replace("{id}", taskId);
        // 2) 轮询至终态
        long deadline = System.currentTimeMillis() + capability.pollTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(capability.pollIntervalMs());
            RestClient.RequestHeadersSpec<?> poll = restClient.get().uri(base + statusPath + authQuery(provider, base + statusPath));
            applyAuthHeaders(poll, provider);
            String statusResp = poll.header("X-Capability-Call-Token", provider.callToken())
                    .retrieve().body(String.class);
            String status = extractStatus(statusResp);
            if ("success".equalsIgnoreCase(status)) {
                // 3) 成功 → 取完整结果
                RestClient.RequestHeadersSpec<?> result = restClient.get().uri(base + resultPath + authQuery(provider, base + resultPath));
                applyAuthHeaders(result, provider);
                String resp = result.header("X-Capability-Call-Token", provider.callToken())
                        .retrieve().body(String.class);
                return resp == null ? Map.of() : resp;
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new IllegalStateException("长任务失败: " + statusResp);
            }
            // pending / running：继续轮询
        }
        throw new IllegalStateException("长任务轮询超时(" + capability.pollTimeoutMs() + "ms)，"
                + "任务可能仍在运行: taskId=" + taskId);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractTaskId(String resp) {
        if (resp == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resp);
            JsonNode v = node.get("task_id");
            if (v == null) {
                v = node.get("taskId");
            }
            return v == null ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractStatus(String resp) {
        if (resp == null) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(resp);
            JsonNode v = node.get("status");
            return v == null ? "" : v.asText();
        } catch (Exception e) {
            return "";
        }
    }

    private CapabilityProvider withStatus(CapabilityProvider p, CapabilityStatus status) {
        return new CapabilityProvider(p.providerId(), p.appName(), p.baseUrl(), p.authType(),
                p.authModel(), p.authHeaderName(), p.authParamName(), p.authValueEncrypted(),
                p.source(), p.mode(), status, p.lastBeatAt(), p.callToken(), p.createdAt());
    }

    private String normalizeMode(String mode) {
        String m = mode == null || mode.isBlank() ? properties.defaultMode() : mode;
        if (!"ap".equalsIgnoreCase(m) && !"cp".equalsIgnoreCase(m)) {
            throw new BizException(ErrorCode.CAPABILITY_INVALID_MODE, m);
        }
        return m.toLowerCase();
    }

    /** 认证模型归一化：none | bearer | header | query（默认 none，兼容旧注册体） */
    private String normalizeAuthModel(String authModel) {
        String m = authModel == null || authModel.isBlank() ? "none" : authModel.toLowerCase();
        switch (m) {
            case "none", "bearer", "header", "query" -> {
                return m;
            }
            default -> throw new BizException(ErrorCode.CAPABILITY_INVALID_MODE, "authModel=" + authModel);
        }
    }

    /**
     * 第三方认证注入（提供者级，与 X-Capability-Call-Token 并存互不冲突）：
     * <ul>
     *   <li>none：不注入</li>
     *   <li>bearer：Authorization: Bearer {key}</li>
     *   <li>header：{authHeaderName}: {key}</li>
     *   <li>query：追加 URL 查询参数（见 {@link #authQuery}）</li>
     * </ul>
     * 密钥解密失败（密钥变更）时静默跳过认证，保证请求不中断。
     */
    private void applyAuthHeaders(RestClient.RequestHeadersSpec<?> spec, CapabilityProvider provider) {
        String model = provider.authModel() == null ? "none" : provider.authModel();
        if ("none".equalsIgnoreCase(model)) {
            return;
        }
        String secret = apiKeyCipher.decrypt(provider.authValueEncrypted());
        if (secret == null || secret.isBlank()) {
            return;
        }
        if ("bearer".equalsIgnoreCase(model)) {
            spec.header("Authorization", "Bearer " + secret);
        } else if ("header".equalsIgnoreCase(model)) {
            String name = provider.authHeaderName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("header 认证必须配置 authHeaderName: " + provider.providerId());
            }
            spec.header(name, secret);
        }
    }

    /** query 认证：返回追加到 URL 的参数字符串（含 ? 或 & 前缀；非 query 模式返回空串） */
    private String authQuery(CapabilityProvider provider, String url) {
        if (!"query".equalsIgnoreCase(provider.authModel() == null ? "none" : provider.authModel())) {
            return "";
        }
        String secret = apiKeyCipher.decrypt(provider.authValueEncrypted());
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String name = provider.authParamName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("query 认证必须配置 authParamName: " + provider.providerId());
        }
        return (url.contains("?") ? "&" : "?")
                + name + "=" + java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 注册请求体（CapabilityController 反序列化目标） */
    public record RegisterRequest(
            String appName,
            String baseUrl,
            String mode,
            String namespace,
            String authModel,
            String authHeaderName,
            String authParamName,
            String authValue,
            String source,
            List<Capability> capabilities) {

        /** 兼容旧注册体（无认证字段）：默认 none */
        public RegisterRequest {
            if (authModel == null || authModel.isBlank()) {
                authModel = "none";
            }
        }
    }
}
