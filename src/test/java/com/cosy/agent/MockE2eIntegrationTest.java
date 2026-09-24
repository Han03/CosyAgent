package com.cosy.agent;

import com.cosy.agent.agent.memory.MemoryLevel;
import com.cosy.agent.agent.memory.MemoryStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mock 模式端到端集成测试：开关开启、无真实 LLM Key，走通
 * HTTP → ReAct 循环 → 工具真实执行 → 记忆持久化 全链路。
 * 概率注入全部置 0，保证行为确定性；记忆持久化断言在 REDIS_IT=true 且 Redis 可用时追加。
 */
@SpringBootTest(properties = {
        "cosy.agent.mock.enabled=true",
        "cosy.agent.mock.latency.enabled=false",
        "cosy.agent.mock.mode=random",
        "cosy.agent.mock.probability.extra-turn=0",
        "cosy.agent.mock.probability.unknown-tool=0",
        "cosy.agent.mock.probability.multi-tool=0",
        "cosy.agent.mock.probability.error=0"})
@AutoConfigureMockMvc
class MockE2eIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemoryStore memoryStore;

    @Test
    void chatRunsFullReActLoopWithMockModel() throws Exception {
        String sessionId = "mock-e2e-" + System.nanoTime();
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"现在几点？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.iterations").value(2))
                .andExpect(jsonPath("$.data.trace[*].toolName", hasItem("get_server_time")))
                .andExpect(jsonPath("$.data.answer").isNotEmpty());

        Assumptions.assumeTrue("true".equals(System.getenv("REDIS_IT")),
                "REDIS_IT=true 且 Redis 可用时验证记忆持久化");
        assertThat(memoryStore.load(MemoryLevel.SESSION, sessionId, "recent")).isPresent();
        assertThat(memoryStore.load(MemoryLevel.WORKING, sessionId, "state")).contains("COMPLETED");
    }

    @Test
    void selfHealScriptRecoversFromUnknownTool() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"mock-self-heal-" + System.nanoTime() + "\",\"message\":\"帮我演示自愈，调用不存在的工具\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.iterations").value(3))
                .andExpect(jsonPath("$.data.trace[*].toolName", hasItem("mock_unknown_tool")))
                .andExpect(jsonPath("$.data.trace[?(@.toolName=='mock_unknown_tool')].content", hasItem(containsString("未知工具"))))
                .andExpect(jsonPath("$.data.trace[*].toolName", hasItem("get_server_time")));
    }
}
