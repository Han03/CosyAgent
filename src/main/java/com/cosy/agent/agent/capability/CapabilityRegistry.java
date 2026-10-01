package com.cosy.agent.agent.capability;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
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
                              RestClient.Builder restClientBuilder) {
        this.toolRegistry = toolRegistry;
        this.store = store;
        this.properties = properties;
        this.resilience = resilience;
        this.restClient = restClientBuilder.build();
        loadPersisted();
    }

    /** 启动恢复：store 中的 CP 提供者与能力装载进内存权威表 */
    private void loadPersisted() {
        List<CapabilityProvider> loaded = store.loadProviders();
        Map<String, List<Capability>> loadedCaps = store.loadCapabilities();
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
        String providerId = UUID.randomUUID().toString().replace("-", "");
        String callToken = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        CapabilityProvider provider = new CapabilityProvider(
                providerId, request.appName(), request.baseUrl(), "shared-secret",
                mode, CapabilityStatus.UP, now, callToken, now);

        List<Capability> caps = new ArrayList<>();
        for (Capability c : request.capabilities()) {
            String namespace = c.namespace() == null || c.namespace().isBlank()
                    ? (request.namespace() == null || request.namespace().isBlank()
                        ? properties.namespace() : request.namespace())
                    : c.namespace();
            Capability full = new Capability(c.name(), c.description(), c.parameters(),
                    c.endpointPath(), c.endpointMethod(), c.retryable(), namespace,
                    c.endpointMode(), c.statusPath(), c.resultPath(),
                    c.pollIntervalMs(), c.pollTimeoutMs());
            String fullName = full.fullName();
            // 工具名全局唯一：仅与"本地工具"冲突拒绝（能力 proxy 已存在 = 同能力多提供者，放行追加实例）
            boolean localConflict = toolRegistry.find(fullName)
                    .map(t -> !(t instanceof CapabilityProxyTool)).orElse(false);
            if (localConflict) {
                throw new BizException(ErrorCode.CAPABILITY_NAME_CONFLICT, fullName);
            }
            caps.add(full);
            capabilities.computeIfAbsent(fullName, k -> new LinkedHashMap<>()).put(providerId, full);
        }
        providers.put(providerId, new AtomicReference<>(provider));

        if (provider.isCp()) {
            store.save(provider, caps); // CP：落库确认（失败即注册失败，强一致）
        }
        refreshTools();
        log.info("能力注册成功: provider={}, mode={}, capabilities={}", providerId, mode, caps.size());
        return new RegisterResult(providerId, caps.stream().map(Capability::fullName).toList(), callToken);
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
                        p.mode(), CapabilityStatus.UP, now, p.callToken(), p.createdAt())
                : new CapabilityProvider(p.providerId(), p.appName(), p.baseUrl(), p.authType(),
                        p.mode(), p.status(), now, p.callToken(), p.createdAt()));
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

    /** CP 主动健康检查：周期探测 {baseUrl}/healthz，失败标 DOWN（不摘除），成功恢复 UP */
    @Scheduled(fixedDelayString = "${cosy.agent.capability.probe.interval:60s}")
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
     * 工具同步：能力表与 ToolRegistry 对齐——新增能力注入 CapabilityProxyTool，
     * 已摘除能力的 proxy 移除。注册/摘除后调用；模型下一轮即看到最新工具集。
     */
    public synchronized void refreshTools() {
        Set<String> active = capabilities.keySet();
        // 移除已无实例的 proxy 工具
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
        if (isGet && !params.isEmpty()) {
            StringBuilder q = new StringBuilder(url.contains("?") ? "&" : "?");
            params.forEach((k, v) -> q.append(k).append('=').append(v));
            url = url + q;
        }
        String body;
        if (isGet) {
            body = restClient.get().uri(url)
                    .header("X-Capability-Call-Token", provider.callToken())
                    .retrieve().body(String.class);
        } else {
            body = restClient.post().uri(url)
                    .header("X-Capability-Call-Token", provider.callToken())
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
        String submitResp = restClient.post().uri(base + capability.endpointPath())
                .header("X-Capability-Call-Token", provider.callToken())
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
            String statusResp = restClient.get().uri(base + statusPath)
                    .header("X-Capability-Call-Token", provider.callToken())
                    .retrieve().body(String.class);
            String status = extractStatus(statusResp);
            if ("success".equalsIgnoreCase(status)) {
                // 3) 成功 → 取完整结果
                String result = restClient.get().uri(base + resultPath)
                        .header("X-Capability-Call-Token", provider.callToken())
                        .retrieve().body(String.class);
                return result == null ? Map.of() : result;
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
                p.mode(), status, p.lastBeatAt(), p.callToken(), p.createdAt());
    }

    private String normalizeMode(String mode) {
        String m = mode == null || mode.isBlank() ? properties.defaultMode() : mode;
        if (!"ap".equalsIgnoreCase(m) && !"cp".equalsIgnoreCase(m)) {
            throw new BizException(ErrorCode.CAPABILITY_INVALID_MODE, m);
        }
        return m.toLowerCase();
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
            List<Capability> capabilities) {
    }
}
