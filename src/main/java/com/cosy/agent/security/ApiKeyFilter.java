package com.cosy.agent.security;

import com.cosy.agent.common.api.Result;
import com.cosy.agent.common.enums.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * API 密钥鉴权过滤器（Step 6 生产化）：配置 cosy.agent.security.api-key 后启用，
 * 对 /api/** 请求校验 X-API-Key 请求头；未匹配返回 401。
 *
 * <p>actuator 探针与健康检查路径放行（不拦 /actuator、/error），便于 K8s 存活探针；
 * 未配置密钥（默认）时过滤器不注册，行为与 Step 5 前一致。</p>
 */
@Component
@ConditionalOnExpression("!'${cosy.agent.security.api-key:}'.isBlank()")
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiKeyFilter(com.cosy.agent.config.SecurityProperties properties) {
        this.apiKey = properties.apiKey();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (apiKey.equals(provided)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.UNAUTHORIZED.code(), "无效或缺失的 X-API-Key")));
    }
}
