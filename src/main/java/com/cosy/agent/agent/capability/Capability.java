package com.cosy.agent.agent.capability;

import java.util.Map;

/**
 * 能力定义（= Agent 的一个远程工具）。
 *
 * <p>调用模式（{@code endpointMode}）：
 * <ul>
 *   <li>{@link #MODE_SYNC sync}（缺省）：单次 HTTP 调用，候选链降级；</li>
 *   <li>{@link #MODE_SUBMIT_POLL submit-poll}：先提交长任务取 taskId，再按间隔轮询
 *       {@code statusPath} 至终态，成功后取 {@code resultPath} 完整结果。
 *       全程仍在候选链容错内（提交/轮询失败切下一候选）。</li>
 * </ul>
 *
 * @param name           能力名（全局唯一，即模型可见的工具名）
 * @param description    能力说明（供 LLM 判断何时调用）
 * @param parameters     入参声明（参数名 → JSON Schema 类型，与 {@code AgentTool.parameters()} 一致）
 * @param endpointPath   提供者侧调用路径（sync=直接调用；submit-poll=提交端点）
 * @param endpointMethod HTTP 方法（POST / GET；submit-poll 固定 POST 提交）
 * @param retryable      是否幂等可重试（套 TOOL 容错时重试安全）
 * @param namespace      命名空间（注册时声明，能力全名 = namespace_name，防跨业务冲突）
 * @param endpointMode   调用模式：sync | submit-poll，缺省 sync
 * @param statusPath     轮询状态路径（含 {id} 占位，替换为 taskId），submit-poll 必填
 * @param resultPath     成功结果路径（含 {id} 占位），submit-poll 必填
 * @param pollIntervalMs 轮询间隔毫秒，缺省 3000
 * @param pollTimeoutMs  最大等待毫秒，缺省 600000（10 分钟）
 */
public record Capability(
        String name,
        String description,
        Map<String, String> parameters,
        String endpointPath,
        String endpointMethod,
        boolean retryable,
        String namespace,
        String endpointMode,
        String statusPath,
        String resultPath,
        long pollIntervalMs,
        long pollTimeoutMs) {

    public static final String MODE_SYNC = "sync";
    public static final String MODE_SUBMIT_POLL = "submit-poll";
    public static final long DEFAULT_POLL_INTERVAL_MS = 3_000;
    public static final long DEFAULT_POLL_TIMEOUT_MS = 600_000;

    /** 旧注册体兼容构造：7 参即同步能力（endpointMode=sync，无轮询配置） */
    public Capability(String name, String description, Map<String, String> parameters,
                      String endpointPath, String endpointMethod, boolean retryable, String namespace) {
        this(name, description, parameters, endpointPath, endpointMethod, retryable, namespace,
                MODE_SYNC, null, null, 0, 0);
    }

    /** 归一化缺省值：空 mode 视为 sync；非法值拒绝（注册校验处抛错）；非正轮询参数取默认 */
    public Capability {
        if (endpointMode == null || endpointMode.isBlank()) {
            endpointMode = MODE_SYNC;
        }
        if (!MODE_SYNC.equals(endpointMode) && !MODE_SUBMIT_POLL.equals(endpointMode)) {
            throw new IllegalArgumentException("endpointMode 仅支持 sync / submit-poll: " + endpointMode);
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        }
        if (pollTimeoutMs <= 0) {
            pollTimeoutMs = DEFAULT_POLL_TIMEOUT_MS;
        }
    }

    /** 是否异步长任务模式 */
    public boolean isSubmitPoll() {
        return MODE_SUBMIT_POLL.equals(endpointMode);
    }

    /** 能力全名：命名空间前缀 + 名称，作为模型可见工具名与全局唯一键 */
    public String fullName() {
        return namespace == null || namespace.isBlank() ? name : namespace + "_" + name;
    }
}
