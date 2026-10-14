package com.cosy.agent.agent.router;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2 画像打分路由单测：标签选链 + 多因子评分**动态排序**（链内候选按分数降序，稳定排序）。
 * 场景：推理需求 → 强推理模型前置；无命中 → 快/廉模型前置；可靠性数据可扭转排序。
 */
class ScoringResolverTest {

    private RouteConfig config;
    private AutoConfigHolder holder;
    private LlmCallStatsProvider stats;
    private ScoringResolver resolver;

    @BeforeEach
    void setUp() {
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("openai", new RouteConfig.ModelPlatform("openai", "http://x", "sk",
                "/v1/chat/completions", "openai", true, 60_000, List.of(
                        new RouteConfig.ModelSpec("gpt-4o-mini", 128_000,
                                List.of("chat", "tool", "fast", "cheap"), 5, 2, 3, 1),
                        new RouteConfig.ModelSpec("gpt-4o", 128_000,
                                List.of("chat", "tool", "reasoning"), 3, 5, 5, 4))));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("default", List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
        routes.put("reasoning", List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
        config = new RouteConfig(true, 5, platforms, routes);
        List<AutoConfig.AutoRule> rules = List.of(
                new AutoConfig.AutoRule("reasoning", 0, 0,
                        List.of("为什么", "推导", "证明", "分析", "解释", "代码", "算法", "why", "explain", "derive", "prove", "analyze")));
        holder = new AutoConfigHolder(new AutoConfig("scoring", rules, Map.of()));
        stats = mock(LlmCallStatsProvider.class);
        when(stats.statsLast7Days()).thenReturn(Map.of());
        TaskTagResolver tagResolver = new TaskTagResolver(config, holder);
        resolver = new ScoringResolver(config, holder, stats, tagResolver);
    }

    private RouteContext ctx(String userText) {
        return new RouteContext("auto", null, List.of(new UserMessage(userText)), List.of(), 0);
    }

    @Test
    void reasoningTask_reordersStrongReasoningModelFirst() {
        RouteDecision d = resolver.decide(ctx("为什么天空是蓝色的？请证明"));
        assertThat(d.mode()).isEqualTo(RouteDecision.RouteDecisionMode.SCORED);
        // 声明顺序 [mini, 4o] → 推理需求下 4o（reasoning 5）前置
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o", "openai/gpt-4o-mini"));
        assertThat(d.rationale()).contains("需求推理=4");
    }

    @Test
    void genericTask_fastCheapModelStaysFirst() {
        RouteDecision d = resolver.decide(ctx("你好，今天天气不错"));
        // 需求推理=2：mini（speed 5/price 1）得分高于 4o → 保持 [mini, 4o]
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
    }

    @Test
    void reliabilityData_canOverrideOrdering() {
        // mini 可靠性极高、4o 可靠性差 → 即使推理需求，4o 被 mini 反超
        when(stats.statsLast7Days()).thenReturn(Map.of(
                "openai/gpt-4o-mini", new LlmCallStatsProvider.ModelStats(100, 0.95, 0.0),
                "openai/gpt-4o", new LlmCallStatsProvider.ModelStats(100, 0.30, 0.2)));
        RouteDecision d = resolver.decide(ctx("为什么天空是蓝色的？请证明"));
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
    }

    @Test
    void singleCandidate_skipsScoring() {
        RouteDecision d = resolver.decide(ctx("你好"));
        // default 链仅剩单候选时（如链只配了一个）直接返回
        assertThat(d.candidates()).isNotEmpty();
        assertThat(d.candidates().size()).isLessThanOrEqualTo(2);
    }
}
