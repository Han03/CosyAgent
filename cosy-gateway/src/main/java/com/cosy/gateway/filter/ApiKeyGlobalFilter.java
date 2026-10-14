package com.cosy.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一 API 鉴权（P2，对应原 agent 侧 ApiKeyFilter 语义）。
 *
 * <p>配置 {@code cosy.gateway.security.api-key} 非空时启用：对 {@code /api/**} 请求校验
 * {@code X-API-Key} 请求头，不匹配返回 401；空（默认）不校验，本地开发直通。
 * 网关校验后不消费业务头，X-Cosy-* 原样透传下游。</p>
 */
@Component
public class ApiKeyGlobalFilter implements GlobalFilter, Ordered {

    private static final String HEADER = "X-API-Key";

    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiKeyGlobalFilter(@Value("${cosy.gateway.security.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (apiKey == null || apiKey.isBlank()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        String provided = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (apiKey.equals(provided)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                    java.util.Map.of("code", 401, "message", "无效或缺失的 X-API-Key"));
        } catch (Exception e) {
            body = "{\"code\":401,\"message\":\"无效或缺失的 X-API-Key\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 优先于 Sentinel 网关过滤器执行（鉴权前置）
        return -200;
    }
}
