package com.cosy.agent.agent.router;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 任务标签路由单测：结构化规则表（配置顺序=优先级）匹配
 * 工具数量 / 上下文规模 / 意图关键词 → 标签链；无命中回退 default。
 */
class TaskTagResolverTest {

    private RouteConfig config;
    private AutoConfigHolder holder;
    private TaskTagResolver resolver;

    @BeforeEach
    void setUp() {
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("openai", new RouteConfig.ModelPlatform("openai", "http://x", "sk",
                "/v1/chat/completions", "openai", true, 60_000, List.of(
                        new RouteConfig.ModelSpec("gpt-4o-mini", 128_000, List.of("chat")))));
        platforms.put("deepseek", new RouteConfig.ModelPlatform("deepseek", "http://y", "sk",
                "/v1/chat/completions", "openai", true, 60_000, List.of(
                        new RouteConfig.ModelSpec("deepseek-chat", 256_000, List.of("chat")))));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("default", List.of("openai/gpt-4o-mini"));
        routes.put("tool-heavy", List.of("openai/gpt-4o"));
        routes.put("long-context", List.of("deepseek/deepseek-chat"));
        routes.put("reasoning", List.of("deepseek/deepseek-reasoner"));
        routes.put("creative", List.of("openai/gpt-4o"));
        config = new RouteConfig(true, 5, platforms, routes);
        List<AutoConfig.AutoRule> rules = List.of(
                new AutoConfig.AutoRule("long-context", 0, 0.6, List.of()),
                new AutoConfig.AutoRule("tool-heavy", 3, 0, List.of()),
                new AutoConfig.AutoRule("reasoning", 0, 0,
                        List.of("为什么", "推导", "证明", "分析", "解释", "代码", "算法", "why", "explain", "derive", "prove", "analyze")),
                new AutoConfig.AutoRule("creative", 0, 0,
                        List.of("写", "创作", "故事", "文案", "润色", "小说", "诗句", "write", "story", "creative", "poem")));
        holder = new AutoConfigHolder(new AutoConfig("task-tag", rules, Map.of()));
        resolver = new TaskTagResolver(config, holder);
    }

    private RouteContext ctx(String userText, int toolCount, int chars) {
        return new RouteContext("auto", null,
                List.of(new UserMessage(userText + "啊".repeat(chars))),
                IntStream.range(0, toolCount)
                        .mapToObj(i -> dummyTool("t" + i))
                        .toList(),
                0);
    }

    /** 仅用于工具数量统计的哑 ToolCallback（schema 足够即可） */
    private static ToolCallback dummyTool(String name) {
        ToolDefinition td = ToolDefinition.builder()
                .name(name)
                .description("d")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return td;
            }

            @Override
            public String call(String arguments) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void toolHeavy_toolsReachThreshold_selectsToolHeavyChain() {
        RouteDecision d = resolver.decide(ctx("帮我查一下天气", 3, 0));
        assertThat(d.mode()).isEqualTo(RouteDecision.RouteDecisionMode.TASK_TAG);
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o"));
    }

    @Test
    void longContext_tokensExceedRatio_selectsLongContextChain() {
        // 登记模型窗口后，超长文本估算 token > 60% 窗口 → long-context（规则顺序在前）
        RouteDecision d = resolver.decide(ctx("这是一个长文档", 0, 700_000));
        assertThat(d.candidates()).isEqualTo(List.of("deepseek/deepseek-chat"));
    }

    @Test
    void reasoningKeyword_selectsReasoningChain() {
        RouteDecision d = resolver.decide(ctx("为什么天空是蓝色的？请证明", 0, 0));
        assertThat(d.candidates()).isEqualTo(List.of("deepseek/deepseek-reasoner"));
    }

    @Test
    void creativeKeyword_selectsCreativeChain() {
        RouteDecision d = resolver.decide(ctx("帮我写一个关于秋天的故事", 0, 0));
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o"));
    }

    @Test
    void noRuleHit_fallsBackToDefaultChain() {
        RouteDecision d = resolver.decide(ctx("你好，今天天气不错", 0, 0));
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o-mini"));
        assertThat(d.rationale()).contains("default");
    }

    @Test
    void keywordMatch_isCaseInsensitive() {
        RouteDecision d = resolver.decide(ctx("Please explain this algorithm", 0, 0));
        assertThat(d.candidates()).isEqualTo(List.of("deepseek/deepseek-reasoner"));
    }
}
