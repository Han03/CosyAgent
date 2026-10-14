package com.cosy.agent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ToolRegistryTest {

    @Test
    void registersAndFindsTools() {
        ToolRegistry registry = new ToolRegistry(List.of(new ServerTimeTool(), new ServerInfoTool()));

        assertThat(registry.names()).containsExactlyInAnyOrder("get_server_time", "get_server_info");
        assertThat(registry.find("get_server_time")).isPresent();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolCanExecute() {
        Map<String, Object> result = (Map<String, Object>) new ServerTimeTool().execute(Map.of());
        assertThat(result).containsKey("time");
    }

    @Test
    void duplicateNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolRegistry(List.of(new ServerTimeTool(), new ServerTimeTool())))
                .withMessageContaining("工具名冲突");
    }
}
