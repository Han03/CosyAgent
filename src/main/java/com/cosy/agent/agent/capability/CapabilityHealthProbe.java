package com.cosy.agent.agent.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力提供者可达性探活（注入判据）：TCP 连通探测（host:port，超时 2s），结果缓存 30s。
 *
 * <p>与业务健康检查（{@code probeCpProviders} 的 /healthz → status UP/DOWN）解耦：
 * 注入提示词的判据是「服务可达」（TCP 可连，如 open-meteo 无 healthz 端点但公网可达），
 * healthz 状态仅用于调用时的候选链降级。这样未启动的提供者（如 CosyStudio 8085 未监听）
 * 自动从提示词摘除，而可达的第三方 API 不受 healthz 端点缺失影响。</p>
 */
@Component
public class CapabilityHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(CapabilityHealthProbe.class);

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private final Map<String, ProbeResult> cache = new ConcurrentHashMap<>();

    /** 提供者是否可达（TCP 连通）；探测失败/解析失败按不可达处理（不阻断） */
    public boolean isReachable(CapabilityProvider provider) {
        String key = provider.baseUrl();
        ProbeResult hit = cache.get(key);
        Instant now = Instant.now();
        if (hit != null && hit.expiresAt().isAfter(now)) {
            return hit.reachable();
        }
        boolean reachable = probe(provider.baseUrl());
        cache.put(key, new ProbeResult(reachable, now.plus(CACHE_TTL)));
        if (!reachable) {
            log.info("能力提供者不可达（不注入提示词）: provider={}, baseUrl={}",
                    provider.appName(), provider.baseUrl());
        }
        return reachable;
    }

    private boolean probe(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private record ProbeResult(boolean reachable, Instant expiresAt) {
    }
}
