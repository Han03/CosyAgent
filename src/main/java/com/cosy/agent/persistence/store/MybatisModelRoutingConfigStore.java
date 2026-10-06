package com.cosy.agent.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cosy.agent.agent.router.ApiKeyCipher;
import com.cosy.agent.agent.router.ModelRoutingConfigStore;
import com.cosy.agent.agent.router.RouteConfig;
import com.cosy.agent.persistence.entity.ModelPlatformEntity;
import com.cosy.agent.persistence.entity.ModelRouteEntity;
import com.cosy.agent.persistence.entity.ModelSpecEntity;
import com.cosy.agent.persistence.mapper.ModelPlatformMapper;
import com.cosy.agent.persistence.mapper.ModelRouteMapper;
import com.cosy.agent.persistence.mapper.ModelSpecMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MyBatis-Plus 模型路由配置持久化（cosy.agent.persistence=mysql 时装配，
 * 替换 MysqlModelRoutingConfigStore / JdbcModelRoutingConfigStore）。
 *
 * <p>表 model_platform / model_spec / model_route；save 为覆盖式事务（清三表再全量插入）；
 * api-key 经 {@link ApiKeyCipher} 加密落库、读取解密；读侧空库返回 empty（回退 YAML 基线）。</p>
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store", havingValue = "mysql")
public class MybatisModelRoutingConfigStore implements ModelRoutingConfigStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisModelRoutingConfigStore.class);

    private final ModelPlatformMapper platformMapper;
    private final ModelSpecMapper specMapper;
    private final ModelRouteMapper routeMapper;
    private final ApiKeyCipher cipher;

    public MybatisModelRoutingConfigStore(ModelPlatformMapper platformMapper,
                                          ModelSpecMapper specMapper,
                                          ModelRouteMapper routeMapper,
                                          ApiKeyCipher cipher) {
        this.platformMapper = platformMapper;
        this.specMapper = specMapper;
        this.routeMapper = routeMapper;
        this.cipher = cipher;
    }

    @Override
    public Optional<RouteConfig> load() {
        try {
            Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
            for (ModelPlatformEntity e : platformMapper.selectList(
                    new QueryWrapper<ModelPlatformEntity>()
                            .orderByAsc("platform_name"))) {
                platforms.put(e.platformName, new RouteConfig.ModelPlatform(
                        e.platformName, e.baseUrl, cipher.decrypt(e.apiKey),
                        e.completionsPath, e.type,
                        e.enabled != null && e.enabled != 0,
                        e.timeoutMs == null ? RouteConfig.DEFAULT_TIMEOUT_MS : e.timeoutMs,
                        loadModels(e.platformName)));
            }
            Map<String, List<String>> routes = new LinkedHashMap<>();
            for (ModelRouteEntity e : routeMapper.selectList(
                    new QueryWrapper<ModelRouteEntity>()
                            .orderByAsc("route_type")
                            .orderByAsc("seq"))) {
                routes.computeIfAbsent(e.routeType, k -> new ArrayList<>()).add(e.candidate);
            }
            if (platforms.isEmpty() && routes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RouteConfig(true, 5, platforms, routes));
        } catch (RuntimeException e) {
            log.warn("读取模型路由配置失败（回退 YAML 基线）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private List<RouteConfig.ModelSpec> loadModels(String platformName) {
        List<RouteConfig.ModelSpec> models = new ArrayList<>();
        for (ModelSpecEntity e : specMapper.selectByPlatform(platformName)) {
            String caps = e.capabilities;
            List<String> capList = (caps == null || caps.isBlank())
                    ? List.of() : List.of(caps.split(","));
            models.add(new RouteConfig.ModelSpec(e.modelId,
                    e.contextWindow == null ? 0 : e.contextWindow, capList));
        }
        return models;
    }

    @Override
    @Transactional
    public void save(RouteConfig config) {
        try {
            platformMapper.delete(null);
            specMapper.delete(null);
            routeMapper.delete(null);
            config.platforms().forEach((name, pf) -> {
                platformMapper.insertFull(name, pf.baseUrl(), cipher.encrypt(pf.apiKey()),
                        pf.type(), pf.enabled() ? 1 : 0, pf.timeoutMs(), pf.completionsPath());
                pf.models().forEach(m -> specMapper.insertFull(name, m.modelId(),
                        m.contextWindow(), String.join(",", m.capabilities())));
            });
            config.routes().forEach((type, candidates) -> {
                for (int i = 0; i < candidates.size(); i++) {
                    routeMapper.insertFull(type, candidates.get(i), i);
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("保存模型路由配置失败（persistence=mysql）: " + e.getMessage(), e);
        }
    }
}
