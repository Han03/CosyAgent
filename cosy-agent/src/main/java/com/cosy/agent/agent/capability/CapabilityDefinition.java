package com.cosy.agent.agent.capability;

import java.util.Map;

/**
 * 远程能力注入元数据（capability 服务 listTools 返回项的本地映射）。
 *
 * @param name        能力全名（= 工具名，供模型选择）
 * @param description 能力说明（供模型理解何时调用）
 * @param parameters  入参声明（参数名 → JSON Schema 类型）
 * @param retryable   是否幂等可重试
 */
public record CapabilityDefinition(
        String name, String description, Map<String, String> parameters, boolean retryable) {
}
