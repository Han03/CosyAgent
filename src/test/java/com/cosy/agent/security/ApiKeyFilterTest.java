package com.cosy.agent.security;

import com.cosy.agent.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 密钥鉴权过滤器单测（Step 6）：正确密钥放行、缺失/错误密钥 401、非 /api 路径放行。
 */
class ApiKeyFilterTest {

    @Test
    void allowsRequestWithMatchingKey() throws ServletException, IOException {
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties("secret-token"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/chat");
        request.addHeader("X-API-Key", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        FilterChain chain = (req, res) -> invoked[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(invoked[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingKeyWith401() throws ServletException, IOException {
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties("secret-token"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        FilterChain chain = (req, res) -> invoked[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(invoked[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("X-API-Key");
    }

    @Test
    void rejectsWrongKeyWith401() throws ServletException, IOException {
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties("secret-token"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/chat");
        request.addHeader("X-API-Key", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void skipsNonApiPaths() throws ServletException, IOException {
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties("secret-token"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        FilterChain chain = (req, res) -> invoked[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(invoked[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
