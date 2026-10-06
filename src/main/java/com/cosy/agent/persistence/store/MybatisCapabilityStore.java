package com.cosy.agent.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cosy.agent.agent.capability.Capability;
import com.cosy.agent.agent.capability.CapabilityProvider;
import com.cosy.agent.agent.capability.CapabilityStatus;
import com.cosy.agent.agent.capability.CapabilityStore;
import com.cosy.agent.persistence.entity.CapabilityEntity;
import com.cosy.agent.persistence.entity.CapabilityProviderEntity;
import com.cosy.agent.persistence.mapper.CapabilityMapper;
import com.cosy.agent.persistence.mapper.CapabilityProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus 能力持久化（cosy.agent.persistence=mysql 时装配，替换 MysqlCapabilityStore）：
 * CP 模式提供者落库、重启恢复；save 为事务（provider + 全量能力 upsert）。
 * 与手写 JDBC 版语义逐一对齐（ON DUPLICATE KEY UPDATE / 外键级联删 / parameters 存储格式）。
 * 与 MemoryCapabilityStore（persistence=memory）按开关互斥装配。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store", havingValue = "mysql")
public class MybatisCapabilityStore implements CapabilityStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisCapabilityStore.class);

    private final CapabilityProviderMapper providerMapper;
    private final CapabilityMapper capabilityMapper;

    public MybatisCapabilityStore(CapabilityProviderMapper providerMapper,
                                  CapabilityMapper capabilityMapper) {
        this.providerMapper = providerMapper;
        this.capabilityMapper = capabilityMapper;
    }

    @Override
    @Transactional
    public void save(CapabilityProvider provider, List<Capability> capabilities) {
        providerMapper.upsert(provider.providerId(), provider.appName(), provider.baseUrl(),
                provider.authType(), provider.authModel(), provider.authHeaderName(),
                provider.authParamName(), provider.authValueEncrypted(), provider.source(),
                provider.callToken(), provider.mode(), provider.status().name(),
                ts(provider.lastBeatAt()), ts(provider.createdAt()));
        for (Capability cap : capabilities) {
            capabilityMapper.upsert(cap.fullName(), provider.providerId(), cap.description(),
                    cap.parameters() == null ? "{}" : cap.parameters().toString(),
                    cap.endpointPath(), cap.endpointMethod(), cap.retryable(), cap.namespace(),
                    cap.enabled());
        }
    }

    @Override
    public List<CapabilityProvider> loadProviders() {
        List<CapabilityProvider> list = new ArrayList<>();
        for (CapabilityProviderEntity e : providerMapper.selectList(null)) {
            list.add(new CapabilityProvider(e.providerId, e.appName, e.baseUrl, e.authType,
                    e.authModel, e.authHeaderName, e.authParamName, e.authValueEncrypted,
                    e.source, e.mode, safeStatus(e.status),
                    e.lastBeatAt, e.callToken, e.createdAt));
        }
        return list;
    }

    @Override
    public Map<String, List<Capability>> loadCapabilities() {
        Map<String, List<Capability>> map = new LinkedHashMap<>();
        for (CapabilityEntity e : capabilityMapper.selectList(null)) {
            Capability cap = new Capability(
                    stripNamespace(e.capabilityName, e.namespace),
                    e.description,
                    parseParameters(e.parametersSchema),
                    e.endpointPath,
                    e.endpointMethod,
                    Boolean.TRUE.equals(e.retryable),
                    e.namespace,
                    Capability.MODE_SYNC, null, null, 0, 0,
                    Boolean.TRUE.equals(e.enabled));
            map.computeIfAbsent(e.providerId, k -> new ArrayList<>()).add(cap);
        }
        return map;
    }

    @Override
    public void updateCapabilityEnabled(String providerId, Capability capability) {
        try {
            capabilityMapper.updateEnabled(capability.fullName(), providerId, capability.enabled());
        } catch (RuntimeException e) {
            log.warn("能力启停落库失败: capability={}, provider={}, cause={}",
                    capability.fullName(), providerId, e.getMessage());
        }
    }

    @Override
    public void deleteProvider(String providerId) {
        providerMapper.delete(new QueryWrapper<CapabilityProviderEntity>()
                .eq("provider_id", providerId));
    }

    // ---- 映射 ----

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static CapabilityStatus safeStatus(String s) {
        try {
            return CapabilityStatus.valueOf(s);
        } catch (Exception e) {
            return CapabilityStatus.UNKNOWN;
        }
    }

    private static Map<String, String> parseParameters(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return map;
        }
        // 存储格式为 Map.toString()：{orderId=string, amount=number}
        String body = raw.trim();
        if (body.startsWith("{") && body.endsWith("}")) {
            body = body.substring(1, body.length() - 1);
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static String stripNamespace(String fullName, String namespace) {
        if (namespace != null && !namespace.isBlank() && fullName.startsWith(namespace + "_")) {
            return fullName.substring(namespace.length() + 1);
        }
        return fullName;
    }
}
