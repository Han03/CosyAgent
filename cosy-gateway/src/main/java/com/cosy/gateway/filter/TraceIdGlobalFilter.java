package com.cosy.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * P4.3 全链路 TraceId（网关入口）。
 *
 * <p>职责：为进入网关的每个请求生成/透传 X-Trace-Id，并注入日志 MDC（key=tid，对应日志 pattern 的 [%X{tid}]）。
 * <ul>
 *   <li>请求头已带 X-Trace-Id（上一跳）则透传，否则生成 UUID 短形式；</li>
 *   <li>转发给下游（agent/knowledge/capability）时携带 X-Trace-Id，响应头也回写；</li>
 *   <li>doFinally 清理 MDC，避免线程复用污染。</li>
 * </ul>
 * 下游服务各自的 TraceIdFilter 读取 X-Trace-Id 继续传播，从而一条用户请求的日志可按 traceId 在 Kibana 中关联。
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    /** 全链路 TraceId 请求/响应头名 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 日志 MDC key（与各服务 logging.pattern 的 [%X{tid}] 对齐） */
    private static final String MDC_KEY = "tid";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        // 响应头回写（filter 阶段响应尚未提交，直接 add 安全）
        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
        ServerHttpRequest mutated = request.mutate().header(TRACE_ID_HEADER, traceId).build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
        return chain.filter(mutatedExchange).doFinally(signalType -> MDC.remove(MDC_KEY));
    }

    /** 最先执行（比限流等 Filter 更早，保证后续 Filter 的日志也能带上 tid） */
    @Override
    public int getOrder() {
        return -100;
    }
}
