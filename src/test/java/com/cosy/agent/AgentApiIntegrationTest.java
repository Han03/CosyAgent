package com.cosy.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent HTTP 接口集成测试：以 Mock LLM 驱动完整 ReAct 链路（工具调用 → 最终回答）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubLlm() {
        AssistantMessage withToolCall = AssistantMessage.builder()
                .content("我需要查询当前时间。")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "get_server_time", "{}")))
                .build();
        AssistantMessage finalAnswer = AssistantMessage.builder()
                .content("当前时间是 2026-09-24 10:00:00。")
                .build();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(withToolCall))),
                        new ChatResponse(List.of(new Generation(finalAnswer))));
    }

    @Test
    void toolsEndpointListsRegisteredTools() throws Exception {
        mockMvc.perform(get("/api/agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasItem("get_server_time")));
    }

    @Test
    void chatEndpointRunsReActLoopAndReturnsAnswer() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"message\":\"现在几点？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.iterations").value(2))
                .andExpect(jsonPath("$.data.answer", containsString("当前时间")));
    }

    @Test
    void chatEndpointRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"message\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
