package com.cosy.agent.agent.router;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

/**
 * 自动路由决策的输入上下文（进程内组装，无 IO）。
 *
 * @param modelChoice 调用方选择（auto | 平台/模型；指定模型时决策器不介入）
 * @param routeType   显式路由类型（有值则决策器不覆盖，走静态链）
 * @param messages    本次完整消息序列（含历史，用于估算上下文规模与提取用户意图）
 * @param tools       动态工具定义（工具数量用于 tool-heavy 判断）
 * @param iteration   ReAct 轮次（预留：多轮后偏向稳定性链）
 */
public record RouteContext(
        String modelChoice,
        String routeType,
        List<Message> messages,
        List<OpenAiApi.FunctionTool> tools,
        int iteration) {

    /** 估算输入 token：字符数 / 4（OpenAI 系近似口径，足够用于窗口比例判断） */
    public int estimatedTokens() {
        long chars = 0;
        if (messages != null) {
            for (Message m : messages) {
                String text = m.getText();
                if (text != null) {
                    chars += text.length();
                }
            }
        }
        return (int) Math.max(1, chars / 4);
    }

    /** 最后一条用户消息文本（意图关键词匹配用）；无则空串 */
    public String lastUserText() {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                String text = m.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    /** 工具数量（null 安全） */
    public int toolCount() {
        return tools == null ? 0 : tools.size();
    }
}
