package com.cosy.agent.agent.capability;

import com.cosy.agent.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 能力注册中心远程客户端（P3.2：经网关/Feign 直达 cosy-capability 服务 8282）。
 *
 * <p>agent 不再持有注册中心：工具注入（{@link CapabilitySync}）拉取 {@link #tools()} 清单，
 * 执行时 {@link CapabilityProxyTool} 委托 {@link #invoke(InvokeRequest)}，
 * 候选链降级 + 四类认证注入均由 capability 服务完成。</p>
 */
@FeignClient(name = "cosy-capability", path = "/api/agent/capability")
public interface CapabilityClient {

    /** 可用能力清单（name/description/parameters/retryable；仅 enabled+UP+可达） */
    @GetMapping("/tools")
    Result<List<Map<String, Object>>> tools();

    /** 调用远程能力（候选链依次降级，返回真实数据） */
    @PostMapping("/invoke")
    Result<Object> invoke(@RequestBody InvokeRequest request);

    record InvokeRequest(String name, Map<String, Object> args) {
    }
}
