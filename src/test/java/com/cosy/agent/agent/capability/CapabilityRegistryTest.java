package com.cosy.agent.agent.capability;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 能力注册中心核心行为测试：
 * 注册/目录/候选链/远程调用降级/心跳摘除(AP)/显式注销(CP)/工具动态同步/名字冲突。
 */
class CapabilityRegistryTest {

    private HttpServer providerA;
    private HttpServer providerB;
    private CapabilityRegistry registry;
    private ToolRegistry toolRegistry;
    private CapabilityProperties props;

    @BeforeEach
    void setUp() throws IOException {
        providerA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerA.createContext("/api/echo", exchange -> {
            byte[] body = "{\"ok\":true,\"from\":\"A\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        providerA.start();

        providerB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerB.createContext("/api/echo", exchange -> {
            byte[] body = "{\"ok\":true,\"from\":\"B\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        providerB.start();

        toolRegistry = new ToolRegistry(List.of());
        props = new CapabilityProperties(true, "", "ap",
                new CapabilityProperties.Heartbeat(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250)),
                new CapabilityProperties.Probe(false, Duration.ofSeconds(60)),
                "memory", "test", Duration.ofSeconds(2), null);

        ResilienceSupport resilience = mock(ResilienceSupport.class);
        when(resilience.execute(any(ResilienceTarget.class), any())).thenAnswer(inv -> {
            var supplier = (java.util.function.Supplier<?>) inv.getArgument(1);
            return supplier.get();
        });

        registry = new CapabilityRegistry(toolRegistry, new MemoryCapabilityStore(), props,
                resilience, RestClient.builder());
    }

    @AfterEach
    void tearDown() {
        providerA.stop(0);
        providerB.stop(0);
    }

    private CapabilityRegistry.RegisterRequest request(String appName, String baseUrl, String mode) {
        return new CapabilityRegistry.RegisterRequest(appName, baseUrl, mode, "test",
                List.of(new Capability("echo", "回显测试能力", Map.of("msg", "string"),
                        "/api/echo", "POST", false, "test")));
    }

    @Test
    void register_injectsProxyTool_andResolvesChain() {
        registry.register(request("app-a", "http://127.0.0.1:" + providerA.getAddress().getPort(), "ap"));

        // 工具注入：模型可见能力工具
        assertThat(toolRegistry.find("test_echo")).isPresent();
        assertThat(toolRegistry.find("test_echo").get().description()).contains("回显");
        // 候选链：UP 优先
        assertThat(registry.resolve("test_echo")).hasSize(1);
        assertThat(registry.resolveQuietly("test_echo").get(0).provider().baseUrl())
                .contains("127.0.0.1:" + providerA.getAddress().getPort());
    }

    @Test
    void call_invokesProvider_overHttp() {
        registry.register(request("app-a", "http://127.0.0.1:" + providerA.getAddress().getPort(), "ap"));

        Object result = toolRegistry.require("test_echo").execute(Map.of("msg", "hi"));
        assertThat(result.toString()).contains("\"from\":\"A\"");
    }

    @Test
    void call_fallsBackToNextCandidate_whenFirstFails() {
        // A 端口未监听（模拟宕机）：先注册 A 再注册 B
        registry.register(request("app-a", "http://127.0.0.1:1", "ap"));
        registry.register(request("app-b", "http://127.0.0.1:" + providerB.getAddress().getPort(), "ap"));

        Object result = registry.call("test_echo", Map.of("msg", "hi"));
        assertThat(result.toString()).contains("\"from\":\"B\"");
        // A 被标记 DOWN：候选链降序
        assertThat(registry.resolve("test_echo").get(0).provider().appName()).isEqualTo("app-b");
    }

    @Test
    void call_throws_whenAllCandidatesFail() {
        registry.register(request("app-a", "http://127.0.0.1:1", "ap"));
        assertThatThrownBy(() -> registry.call("test_echo", Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("全部候选");
    }

    @Test
    void apHeartbeatExpiry_removesProvider() throws InterruptedException {
        registry.register(request("app-a", "http://127.0.0.1:" + providerA.getAddress().getPort(), "ap"));
        assertThat(registry.capabilityNames()).contains("test_echo");

        Thread.sleep(400); // 超过 expireAfter(250ms)
        registry.sweepHeartbeat();

        assertThat(registry.capabilityNames()).isEmpty();
        assertThat(toolRegistry.find("test_echo")).isEmpty(); // 工具同步摘除
    }

    @Test
    void apHeartbeat_keepsProviderAlive() throws InterruptedException {
        registry.register(request("app-a", "http://127.0.0.1:" + providerA.getAddress().getPort(), "ap"));
        String pid = registry.allProviders().get(0).providerId();

        Thread.sleep(120); // 超过 suspectAfter(100ms) 但未到 expireAfter
        registry.heartbeat(pid); // 续约
        registry.sweepHeartbeat();
        assertThat(registry.capabilityNames()).contains("test_echo");
    }

    @Test
    void cpProvider_notRemovedByHeartbeatExpiry_butByDeregister() throws InterruptedException {
        registry.register(request("app-a", "http://127.0.0.1:" + providerA.getAddress().getPort(), "cp"));
        String pid = registry.allProviders().get(0).providerId();

        Thread.sleep(400); // 远超 AP 摘除窗口
        registry.sweepHeartbeat(); // CP 不受心跳摘除影响
        assertThat(registry.capabilityNames()).contains("test_echo");

        registry.deregister(pid); // 仅显式注销移除
        assertThat(registry.capabilityNames()).isEmpty();
        assertThat(toolRegistry.find("test_echo")).isEmpty();
    }

    @Test
    void register_rejectsNameConflictWithLocalTool() {
        toolRegistry.register(new com.cosy.agent.agent.tool.AgentTool() {
            @Override
            public String name() {
                return "test_echo";
            }

            @Override
            public String description() {
                return "本地工具";
            }

            @Override
            public Object execute(Map<String, Object> args) {
                return null;
            }
        });
        assertThatThrownBy(() -> registry.register(request("app-a", "http://127.0.0.1:1", "ap")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void register_rejectsInvalidMode() {
        assertThatThrownBy(() -> registry.register(request("app-a", "http://127.0.0.1:1", "raft")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ap/cp");
    }
}
