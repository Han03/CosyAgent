package com.cosy.agent.agent.capability;

import java.util.Map;

/**
 * 能力定义（= Agent 的一个远程工具）。
 *
 * @param name          能力名（全局唯一，即模型可见的工具名）
 * @param description   能力说明（供 LLM 判断何时调用）
 * @param parameters    入参声明（参数名 → JSON Schema 类型，与 {@code AgentTool.parameters()} 一致）
 * @param endpointPath  提供者侧调用路径（如 /api/order/query）
 * @param endpointMethod HTTP 方法（POST / GET）
 * @param retryable     是否幂等可重试（套 TOOL 容错时重试安全）
 * @param namespace     命名空间（注册时声明，能力全名 = namespace_name，防跨业务冲突）
 */
public record Capability(
        String name,
        String description,
        Map<String, String> parameters,
        String endpointPath,
        String endpointMethod,
        boolean retryable,
        String namespace) {

    /** 能力全名：命名空间前缀 + 名称，作为模型可见工具名与全局唯一键 */
    public String fullName() {
        return namespace == null || namespace.isBlank() ? name : namespace + "_" + name;
    }
}
