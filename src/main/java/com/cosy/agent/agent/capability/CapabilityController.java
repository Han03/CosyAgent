package com.cosy.agent.agent.capability;

import com.cosy.agent.common.api.Result;
import com.cosy.agent.common.exception.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力注册中心 API（提供者 → CosyAgent 的注册通道 + 前端管理）。
 *
 * <p>注册/心跳/注销校验 {@code X-Capability-Token}（配置 {@code cosy.agent.capability.token}，
 * 空 = 不校验）；管理目录/提供者 CRUD 走既有鉴权（cosy.security.api-key）。</p>
 *
 * <ul>
 *   <li>POST /api/capabilities/register —— 外部项目注册提供者及其能力（mode=ap|cp）</li>
 *   <li>POST /api/capabilities/beat —— AP 心跳续约</li>
 *   <li>POST /api/capabilities/deregister —— 注销</li>
 *   <li>GET /api/agent/capabilities —— 能力目录（脱敏：不含 callToken/密钥）</li>
 *   <li>GET/POST/PUT/DELETE /api/agent/capabilities/providers[/{providerId}] —— 前端管理（console 来源）</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(prefix = "cosy.agent.capability", name = "enabled", havingValue = "true")
public class CapabilityController {

    private static final String TOKEN_HEADER = "X-Capability-Token";
    /** 前端管理端点固定标记（能力目录区分注册来源） */
    private static final String SOURCE_CONSOLE = "console";

    private final CapabilityRegistry registry;
    private final CapabilityProperties properties;

    public CapabilityController(CapabilityRegistry registry, CapabilityProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostMapping("/api/capabilities/register")
    public Result<CapabilityRegistry.RegisterResult> register(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody CapabilityRegistry.RegisterRequest request) {
        checkToken(token);
        if (request.appName() == null || request.appName().isBlank()
                || request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName 与 baseUrl 必填");
        }
        if (request.capabilities() == null || request.capabilities().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capabilities 至少一项");
        }
        return Result.ok(registry.register(request));
    }

    @PostMapping("/api/capabilities/beat")
    public Result<Void> beat(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody Map<String, String> body) {
        checkToken(token);
        String providerId = body.get("providerId");
        if (providerId == null || providerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerId 必填");
        }
        registry.heartbeat(providerId);
        return Result.ok();
    }

    @PostMapping("/api/capabilities/deregister")
    public Result<Void> deregister(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody Map<String, String> body) {
        checkToken(token);
        String providerId = body.get("providerId");
        if (providerId == null || providerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerId 必填");
        }
        registry.deregister(providerId);
        return Result.ok();
    }

    /** 能力目录（管理/客户端浏览）：脱敏展示，不含 callToken/认证密钥 */
    @GetMapping("/api/agent/capabilities")
    public Result<List<Map<String, Object>>> catalog() {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (String name : registry.capabilityNames()) {
            List<CapabilityRegistry.ActiveCapability> chain = registry.resolveQuietly(name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("description", registry.describe(name));
            item.put("instances", chain.stream().map(ac -> {
                Map<String, Object> inst = new LinkedHashMap<>();
                inst.put("providerId", ac.provider().providerId());
                inst.put("appName", ac.provider().appName());
                inst.put("baseUrl", ac.provider().baseUrl());
                inst.put("mode", ac.provider().mode());
                inst.put("endpointMode", ac.capability().endpointMode());
                inst.put("status", ac.provider().status().name());
                inst.put("retryable", ac.capability().retryable());
                inst.put("enabled", ac.capability().enabled());
                inst.put("authModel", ac.provider().authModel());
                inst.put("source", ac.provider().source());
                return inst;
            }).toList());
            items.add(item);
        }
        return Result.ok(items);
    }

    // ---- 前端管理端点（console 来源） ----

    /** 提供者列表（管理）：密钥掩码展示，不含 callToken */
    @GetMapping("/api/agent/capabilities/providers")
    public Result<List<Map<String, Object>>> providers() {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (CapabilityProvider p : registry.allProviders()) {
            items.add(providerSummary(p));
        }
        return Result.ok(items);
    }

    /** 提供者详情（编辑回显）：含能力完整定义；密钥仅掩码 */
    @GetMapping("/api/agent/capabilities/providers/{providerId}")
    public Result<Map<String, Object>> providerDetail(@PathVariable String providerId) {
        CapabilityProvider p = registry.allProviders().stream()
                .filter(x -> x.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new BizException(com.cosy.agent.common.enums.ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND, providerId));
        Map<String, Object> detail = providerSummary(p);
        detail.put("capabilities", registry.capabilitiesOf(providerId).stream().map(c -> {
            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("name", c.name());
            cap.put("description", c.description());
            cap.put("parameters", c.parameters());
            cap.put("endpointPath", c.endpointPath());
            cap.put("endpointMethod", c.endpointMethod());
            cap.put("retryable", c.retryable());
            cap.put("endpointMode", c.endpointMode());
            cap.put("statusPath", c.statusPath());
            cap.put("resultPath", c.resultPath());
            cap.put("pollIntervalMs", c.pollIntervalMs());
            cap.put("pollTimeoutMs", c.pollTimeoutMs());
            cap.put("enabled", c.enabled());
            return cap;
        }).toList());
        return Result.ok(detail);
    }

    /** 创建第三方能力提供者（前端表单）：复用注册逻辑，来源固定 console */
    @PostMapping("/api/agent/capabilities/providers")
    public Result<CapabilityRegistry.RegisterResult> createProvider(
            @RequestBody CapabilityRegistry.RegisterRequest request) {
        if (request.capabilities() == null || request.capabilities().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capabilities 至少一项");
        }
        CapabilityRegistry.RegisterRequest withSource = withSource(request, SOURCE_CONSOLE);
        return Result.ok(registry.register(withSource));
    }

    /** 更新提供者（保留 providerId/密钥未传时保持原值）；能力全量替换 */
    @PutMapping("/api/agent/capabilities/providers/{providerId}")
    public Result<Map<String, String>> updateProvider(@PathVariable String providerId,
                                                      @RequestBody CapabilityRegistry.RegisterRequest request) {
        registry.updateProvider(providerId, withSource(request, SOURCE_CONSOLE));
        return Result.ok(Map.of("providerId", providerId));
    }

    /** 删除提供者（= deregister，级联删能力） */
    @DeleteMapping("/api/agent/capabilities/providers/{providerId}")
    public Result<Void> deleteProvider(@PathVariable String providerId) {
        registry.deregister(providerId);
        return Result.ok();
    }

    /** 能力启停（上线/下线）：enabled=false → 不注入提示词、不参与寻址 */
    @PutMapping("/api/agent/capabilities/{capabilityName}/enabled")
    public Result<Map<String, Object>> setEnabled(@PathVariable String capabilityName,
                                                  @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 必填");
        }
        int affected = registry.setEnabled(capabilityName, enabled);
        return Result.ok(Map.of("capability", capabilityName, "enabled", enabled, "instances", affected));
    }

    /** 提供者摘要（列表/详情共用）：密钥掩码、无 callToken */
    private Map<String, Object> providerSummary(CapabilityProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerId", p.providerId());
        m.put("appName", p.appName());
        m.put("baseUrl", p.baseUrl());
        m.put("mode", p.mode());
        m.put("status", p.status().name());
        m.put("source", p.source());
        m.put("authModel", p.authModel());
        m.put("authHeaderName", p.authHeaderName());
        m.put("authParamName", p.authParamName());
        m.put("authValueMasked", mask(p.authValueEncrypted()));
        m.put("capabilityCount", registry.capabilitiesOf(p.providerId()).size());
        m.put("createdAt", p.createdAt() == null ? null : p.createdAt().toString());
        m.put("lastBeatAt", p.lastBeatAt() == null ? null : p.lastBeatAt().toString());
        return m;
    }

    /** 密钥掩码：仅展示首尾各 2 字符，其余 *（密文存储时按密文形态掩码） */
    private static String mask(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        return encrypted.length() <= 4 ? "****" : encrypted.substring(0, 2) + "****" + encrypted.substring(encrypted.length() - 2);
    }

    private static CapabilityRegistry.RegisterRequest withSource(CapabilityRegistry.RegisterRequest r, String source) {
        return new CapabilityRegistry.RegisterRequest(
                r.appName(), r.baseUrl(), r.mode(), r.namespace(),
                r.authModel(), r.authHeaderName(), r.authParamName(), r.authValue(), source,
                r.capabilities());
    }

    private void checkToken(String token) {
        if (!properties.token().isBlank() && !properties.token().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效的注册令牌");
        }
    }
}
