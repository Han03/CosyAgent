package com.cosy.capability.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * P4.3 全链路 TraceId（服务侧）。
 *
 * <p>职责：读取网关透传的 X-Trace-Id 注入日志 MDC（key=tid），缺失则本地生成；
 * 响应头回写 traceId；finally 清理 MDC 防止线程复用污染。
 * 与网关 TraceIdGlobalFilter 配合，实现「用户请求 → 网关 → capability」的日志按 traceId 可关联。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    /** 全链路 TraceId 请求/响应头名（与网关一致） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 日志 MDC key（与 logging.pattern 的 [%X{tid}] 对齐） */
    private static final String MDC_KEY = "tid";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
