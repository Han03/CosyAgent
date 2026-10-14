package com.cosy.agent.agent.router;

import com.cosy.agent.TestResilience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自动路由编排单测：档位分发（static/task-tag/scoring）+ 决策异常回退静态链。
 */
class AutoRouterTest {

    private RouteConfig config;
    private AutoConfigHolder holder;
    private AutoRouter autoRouter;

    @BeforeEach
    void setUp() {
        Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
        platforms.put("openai", new RouteConfig.ModelPlatform("openai", "http://x", "sk", "/v1/chat/completions"));
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("default", List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
        routes.put("reasoning", List.of("openai/gpt-4o"));
        config = new RouteConfig(true, 5, platforms, routes);
        List<AutoConfig.AutoRule> rules = List.of(
                new AutoConfig.AutoRule("reasoning", 0, 0,
                        List.of("为什么", "推导", "证明", "分析", "解释", "代码", "算法", "why", "explain", "derive", "prove", "analyze")));
        holder = new AutoConfigHolder(new AutoConfig("task-tag", rules, Map.of()));
        TaskTagResolver tagResolver = new TaskTagResolver(config, holder);
        ScoringResolver scoringResolver = new ScoringResolver(config, holder,
                mock(LlmCallStatsProvider.class), tagResolver);
        autoRouter = new AutoRouter(holder, config, tagResolver, scoringResolver);
    }

    private RouteContext ctx(String userText) {
        return new RouteContext("auto", null, List.of(new UserMessage(userText)), List.of(), 0);
    }

    @Test
    void staticMode_returnsStaticChain() {
        holder.refresh(AutoConfig.defaults());
        RouteDecision d = autoRouter.decide(ctx("为什么天空是蓝色的？请证明"));
        assertThat(d.mode()).isEqualTo(RouteDecision.RouteDecisionMode.STATIC);
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o-mini", "openai/gpt-4o"));
    }

    @Test
    void taskTagMode_resolvesByRules() {
        holder.refresh(new AutoConfig("task-tag", holder.current().rules(), Map.of()));
        RouteDecision d = autoRouter.decide(ctx("为什么天空是蓝色的？请证明"));
        assertThat(d.mode()).isEqualTo(RouteDecision.RouteDecisionMode.TASK_TAG);
        assertThat(d.candidates()).isEqualTo(List.of("openai/gpt-4o"));
    }

    @Test
    void scoringMode_reordersCandidates() {
        holder.refresh(new AutoConfig("scoring", holder.current().rules(), Map.of()));
        RouteDecision d = autoRouter.decide(ctx("为什么天空是蓝色的？请证明"));
        assertThat(d.mode()).isEqualTo(RouteDecision.RouteDecisionMode.SCORED);
        assertThat(d.candidates()).isNotEmpty();
    }

    @Test
    void unknownMode_fallsBackToStatic() {
        holder.refresh(new AutoConfig("unknown-mode", List.of(), Map.of()));
        RouteDecision d = autoRouter.decide(ctx("你好"));
        assertThat(d.mode()).isEqualTo(RouteDecision.RouteDecisionMode.STATIC);
    }

    @Test
    void modelRouter_integratesAutoRouterForAutoWithoutType() {
        ChatModel modelA = mock(ChatModel.class);
        when(modelA.call(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(response("a"));
        ModelPlatformRegistry registry = new ModelPlatformRegistry(
                OpenAiChatOptions.builder().build(),
                (pf, m) -> modelA);
        ModelRouter router = new ModelRouter(mock(ChatModel.class), OpenAiChatOptions.builder().build(),
                TestResilience.defaultResilience(), config, registry);
        router.setAutoRouter(autoRouter);

        // auto + 无显式 routeType → 决策器（task-tag 命中 reasoning → openai/gpt-4o）
        ChatResponse resp = router.call(new Prompt(List.of(new UserMessage("为什么天空是蓝色的？请证明")),
                OpenAiChatOptions.builder().build()), "auto", null).response();
        assertThat(resp).isNotNull();
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())));
    }
}
