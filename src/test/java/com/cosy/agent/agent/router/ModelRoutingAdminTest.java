package com.cosy.agent.agent.router;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型管理平台化（细粒度 CRUD）单测：
 * 平台/模型/规则的增删改、api-key 掩码与保持、删除引用校验、候选平台校验。
 */
class ModelRoutingAdminTest {

    private ModelRouter router;
    private ModelRoutingConfigStore store;
    private ModelRoutingAdmin admin;
    private AtomicReference<RouteConfig> state;

    @BeforeEach
    void setUp() {
        router = mock(ModelRouter.class);
        store = mock(ModelRoutingConfigStore.class);
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("openai", new RouteConfig.ModelPlatform(
                "openai", "http://localhost:1", "sk-secret-1234567890", "/v1/chat/completions",
                "openai", true, 60_000, List.of()));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put(RouteConfig.DEFAULT_ROUTE, List.of("openai/gpt-4o-mini"));
        state = new AtomicReference<>(new RouteConfig(true, 5, platforms, routes));
        // 模拟热更新：refresh 推进内存配置（与真实 ModelRouter.refresh 一致）
        when(router.currentConfig()).thenAnswer(inv -> state.get());
        doAnswer(inv -> {
            state.set(inv.getArgument(0));
            return null;
        }).when(router).refresh(any(RouteConfig.class));
        admin = new ModelRoutingAdmin(router, store);
    }

    @Test
    void listProviders_masksApiKey_neverLeaksPlaintext() {
        var list = admin.listProviders();
        assertThat(list).hasSize(1);
        Map<String, Object> item = list.get(0);
        assertThat(item.get("maskedApiKey")).isEqualTo("sk-s****7890");
        assertThat(item.get("apiKeyConfigured")).isEqualTo(true);
        assertThat(item.get("modelCount")).isEqualTo(0);
        // 列表整体不得出现明文 key
        assertThat(list.toString()).doesNotContain("sk-secret-1234567890");
    }

    @Test
    void createProvider_persistsWithPlainKeyForRuntime_andRefreshes() {
        admin.createProvider("aliyun", new ModelRoutingAdmin.ProviderDto(
                "http://ali.example", "sk-ali-12345678", "/v1/chat/completions",
                "openai", true, 30_000));
        ArgumentCaptor<RouteConfig> cap = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store).save(cap.capture());
        verify(router).refresh(cap.getValue());
        RouteConfig.ModelPlatform pf = cap.getValue().platforms().get("aliyun");
        assertThat(pf.apiKey()).isEqualTo("sk-ali-12345678"); // 执行器内部明文，加密在 store 落库层
        assertThat(pf.timeoutMs()).isEqualTo(30_000);
    }

    @Test
    void createProvider_requiresApiKey() {
        assertThatThrownBy(() -> admin.createProvider("x", new ModelRoutingAdmin.ProviderDto(
                "http://x", null, null, null, null, null)))
                .isInstanceOf(com.cosy.agent.common.exception.BizException.class);
    }

    @Test
    void createProvider_duplicateName_rejected() {
        assertThatThrownBy(() -> admin.createProvider("openai", new ModelRoutingAdmin.ProviderDto(
                "http://x", "sk-x", null, null, null, null)))
                .isInstanceOf(com.cosy.agent.common.exception.BizException.class);
    }

    @Test
    void updateProvider_blankApiKeyKeepsExisting() {
        admin.updateProvider("openai", new ModelRoutingAdmin.ProviderDto(
                "http://new.example", "", "/chat/completions", "zhipu", false, 120_000));
        ArgumentCaptor<RouteConfig> cap = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store).save(cap.capture());
        RouteConfig.ModelPlatform pf = cap.getValue().platforms().get("openai");
        assertThat(pf.apiKey()).isEqualTo("sk-secret-1234567890"); // 空 key = 保持原值
        assertThat(pf.baseUrl()).isEqualTo("http://new.example");
        assertThat(pf.type()).isEqualTo("zhipu");
        assertThat(pf.enabled()).isFalse();
        assertThat(pf.timeoutMs()).isEqualTo(120_000);
    }

    @Test
    void deleteProvider_referencedByRoute_rejected() {
        assertThatThrownBy(() -> admin.deleteProvider("openai"))
                .isInstanceOf(com.cosy.agent.common.exception.BizException.class);
    }

    @Test
    void deleteProvider_unreferenced_removed() {
        admin.updateRules(Map.of(RouteConfig.DEFAULT_ROUTE, List.of()));
        admin.deleteProvider("openai");
        ArgumentCaptor<RouteConfig> cap = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store, org.mockito.Mockito.times(2)).save(cap.capture()); // updateRules + deleteProvider
        assertThat(cap.getValue().platforms()).doesNotContainKey("openai");
    }

    @Test
    void addUpdateDeleteModel_flow() {
        admin.addModel("openai", "gpt-4o-mini", new ModelRoutingAdmin.ModelDto(128_000, List.of("chat", "tool-call")));
        ArgumentCaptor<RouteConfig> cap1 = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store, org.mockito.Mockito.times(1)).save(cap1.capture());
        assertThat(cap1.getValue().platforms().get("openai").models())
                .anyMatch(m -> m.modelId().equals("gpt-4o-mini") && m.contextWindow() == 128_000);

        admin.updateModel("openai", "gpt-4o-mini", new ModelRoutingAdmin.ModelDto(200_000, List.of("chat")));
        ArgumentCaptor<RouteConfig> cap2 = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store, org.mockito.Mockito.times(2)).save(cap2.capture());
        assertThat(cap2.getValue().platforms().get("openai").models())
                .anyMatch(m -> m.modelId().equals("gpt-4o-mini") && m.contextWindow() == 200_000
                        && m.capabilities().equals(List.of("chat")));

        // 清空规则引用后可删除（baseline default 链引用 openai/gpt-4o-mini）
        admin.updateRules(Map.of(RouteConfig.DEFAULT_ROUTE, List.of()));
        admin.deleteModel("openai", "gpt-4o-mini");
        ArgumentCaptor<RouteConfig> cap3 = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store, org.mockito.Mockito.times(4)).save(cap3.capture());
        assertThat(cap3.getValue().platforms().get("openai").models()).isEmpty();
    }

    @Test
    void deleteModel_referencedByRule_rejected() {
        admin.addModel("openai", "gpt-4o-mini", new ModelRoutingAdmin.ModelDto(128_000, List.of("chat")));
        // baseline default 链引用 openai/gpt-4o-mini → 删除被拒绝
        assertThatThrownBy(() -> admin.deleteModel("openai", "gpt-4o-mini"))
                .isInstanceOf(com.cosy.agent.common.exception.BizException.class);
        // 清空规则引用后可删除
        admin.updateRules(Map.of(RouteConfig.DEFAULT_ROUTE, List.of()));
        admin.deleteModel("openai", "gpt-4o-mini");
    }

    @Test
    void updateRules_autoRegistersMissingModel_andReturnsHint() {
        // 先注册 zhipu 平台（无模型规格）；规则引用其未登记模型 → 自动补登记并返回提示
        admin.createProvider("zhipu", new ModelRoutingAdmin.ProviderDto(
                "http://zhipu.example", "sk-zhipu", "/chat/completions", "openai", true, 60_000));
        Map<String, Object> result = admin.updateRules(Map.of(
                "default", List.of("zhipu/glm-4-flash", "zhipu/gpt-x")));
        @SuppressWarnings("unchecked")
        List<String> registered = (List<String>) result.get("registeredMissing");
        assertThat(registered).containsExactly("zhipu/glm-4-flash", "zhipu/gpt-x");
        ArgumentCaptor<RouteConfig> cap = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().platforms().get("zhipu").models())
                .anyMatch(m -> m.modelId().equals("gpt-x") && m.contextWindow() == 0
                        && m.capabilities().equals(List.of("chat")));
    }

    @Test
    void updateRules_validatesCandidatePlatformRegistered() {
        assertThatThrownBy(() -> admin.updateRules(Map.of("default", List.of("unknown/gpt-x"))))
                .isInstanceOf(com.cosy.agent.common.exception.BizException.class);
    }

    @Test
    void updateRules_ordersCandidatesByListOrder() {
        admin.updateRules(Map.of("default", List.of("openai/gpt-4o", "openai/gpt-4o-mini")));
        ArgumentCaptor<RouteConfig> cap = ArgumentCaptor.forClass(RouteConfig.class);
        verify(store).save(cap.capture());
        assertThat(cap.getValue().routes().get("default"))
                .containsExactly("openai/gpt-4o", "openai/gpt-4o-mini");
    }

    @Test
    void providerDetail_includesModelMetadata() {
        admin.addModel("openai", "gpt-4o", new ModelRoutingAdmin.ModelDto(128_000, List.of("chat")));
        Map<String, Object> detail = admin.providerDetail("openai");
        assertThat(detail.get("maskedApiKey")).isEqualTo("sk-s****7890");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) detail.get("models");
        assertThat(models).anyMatch(m -> "gpt-4o".equals(m.get("modelId"))
                && m.get("contextWindow").equals(128_000));
    }
}
