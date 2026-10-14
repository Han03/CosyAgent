package com.cosy.agent.agent.mock;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 预置剧本库：覆盖单工具 / 多轮多工具 / 未知工具自愈 / 超迭代 等编排路径。
 * 候选工具名与 {@code ToolRegistry} 已注册工具一致（self-heal 刻意使用未注册名触发自愈路径）。
 */
@Component
public class MockScriptLibrary {

    private static final String UNKNOWN_TOOL = "mock_unknown_tool";

    private final List<MockScript> scripts;

    public MockScriptLibrary() {
        this.scripts = List.of(
                MockScript.normal("time", "查询当前时间",
                        List.of(
                                MockTurn.toolCall(MockAction.of("get_server_time")),
                                MockTurn.finalAnswer(
                                        "查询完成：{result}",
                                        "已完成查询，结果如下：{result}",
                                        "结果已获取：{result}"))),
                MockScript.normal("server-info", "查询服务器信息",
                        List.of(
                                MockTurn.toolCall(MockAction.of("get_server_info")),
                                MockTurn.finalAnswer(
                                        "服务器信息已获取：{result}",
                                        "查询结果：{result}",
                                        "信息如下：{result}"))),
                MockScript.normal("combined", "综合巡检（多轮多工具）",
                        List.of(
                                MockTurn.toolCall(MockAction.of("get_server_time")),
                                MockTurn.toolCall(MockAction.of("get_server_info")),
                                MockTurn.finalAnswer(
                                        "综合巡检完成，结果汇总：{result}",
                                        "巡检结果：{result}",
                                        "汇总如下：{result}"))),
                MockScript.deterministic("self-heal", "未知工具自愈（先调用不存在工具再改用真实工具）",
                        List.of(
                                MockTurn.toolCall(MockAction.of(UNKNOWN_TOOL)),
                                MockTurn.toolCall(MockAction.of("get_server_time")),
                                MockTurn.finalAnswer(
                                        "已纠正工具选择并完成：{result}",
                                        "最终结果：{result}"))),
                MockScript.loopLimit("loop-limit", "持续调用直至超迭代终止",
                        List.of(MockTurn.toolCall(MockAction.of("get_server_time")))));
    }

    public Optional<MockScript> byId(String id) {
        return scripts.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public List<MockScript> all() {
        return scripts;
    }
}
