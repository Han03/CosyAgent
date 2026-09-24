package com.cosy.agent.agent.router;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次路由调用的结果：响应 + 降级轨迹（审计用）。
 *
 * @param response       最终命中的模型响应
 * @param chosenCandidate 实际命中的候选（"平台/模型"）；回退路径下为最后一个成功候选
 * @param attempts       依次尝试过的候选（含失败与最终命中）
 * @param fallbackReasons 每个失败候选的降级原因（与 attempts 中失败项一一对应）
 */
public record RouteResult(
        ChatResponse response,
        String chosenCandidate,
        List<String> attempts,
        List<String> fallbackReasons) {

    public static RouteResult ok(ChatResponse response, String chosenCandidate, List<String> attempts,
                                 List<String> fallbackReasons) {
        return new RouteResult(response, chosenCandidate, new ArrayList<>(attempts),
                new ArrayList<>(fallbackReasons));
    }

    /** 单候选直达（enabled=false 回退路径或指定模型成功） */
    public static RouteResult direct(ChatResponse response, String candidate) {
        return new RouteResult(response, candidate, List.of(candidate), List.of());
    }
}
