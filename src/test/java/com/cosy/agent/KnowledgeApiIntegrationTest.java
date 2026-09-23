package com.cosy.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库接口集成测试（默认 memory 存储，无外部依赖）：
 * 文档入库（切分）→ 检索命中 → 命名空间隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void upsertThenSearchReturnsHit() throws Exception {
        mockMvc.perform(post("/api/agent/knowledge/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"hr\",\"docId\":\"api-doc-1\",\"content\":\"重置密码的操作步骤：进入设置页点击重置并验证身份。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.namespace").value("hr"))
                .andExpect(jsonPath("$.data.chunks").value(greaterThanOrEqualTo(1)));

        mockMvc.perform(post("/api/agent/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"hr\",\"query\":\"如何重置密码\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hits[0].content", org.hamcrest.Matchers.containsString("重置密码")));
    }

    @Test
    void namespaceIsolationViaApi() throws Exception {
        mockMvc.perform(post("/api/agent/knowledge/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"it\",\"docId\":\"api-doc-2\",\"content\":\"服务器部署手册：配置防火墙与反向代理。\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/agent/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"it\",\"query\":\"重置密码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits").isEmpty());
    }
}
