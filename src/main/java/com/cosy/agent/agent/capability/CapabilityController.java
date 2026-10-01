package com.cosy.agent.agent.capability;

import com.cosy.agent.common.api.Result;
import com.cosy.agent.common.exception.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力注册中心 API（提供者 → CosyAgent 的注册通道）。
 *
 * <p>注册/心跳/注销校验 {@code X-Capability-Token}（配置 {@code cosy.agent.capability.token}，
 * 空 = 不校验）；管理目录走既有鉴权（cosy.security.api-key）。</p>
 *
 * <ul>
 *   <li>POST /api/capabilities/register —— 注册提供者及其能力（mode=ap|cp）</li>
 *   <li>POST /api/capabilities/beat —— AP 心跳续约</li>
 *   <li>POST /api/capabilities/deregister —— 注销</li>
 *   <li>GET /api/agent/capabilities —— 能力目录（脱敏：不含 callToken）</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(prefix = "cosy.agent.capability", name = "enabled", havingValue = "true")
public class CapabilityController {

    private static final String TOKEN_HEADER = "X-Capability-Token";

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

    /** 能力目录（管理/客户端浏览）：脱敏展示，不含 callToken */
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
                return inst;
            }).toList());
            items.add(item);
        }
        return Result.ok(items);
    }

    private void checkToken(String token) {
        if (!properties.token().isBlank() && !properties.token().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效的注册令牌");
        }
    }
}
