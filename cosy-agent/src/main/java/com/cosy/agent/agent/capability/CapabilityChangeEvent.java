package com.cosy.agent.agent.capability;

/**
 * 能力变更事件（P4.1）：capability 服务注册/更新/摘除/启停后发布，
 * agent 订阅后触发能力清单立即同步（替代纯轮询等待收敛）。
 *
 * @param provider   提供方 id（如 cosy-studio）
 * @param capability 能力名（action=register 时可为空）
 * @param action     register | update | deregister | enable | disable | sweep
 * @param ts         ISO 时间戳
 */
public record CapabilityChangeEvent(String provider, String capability, String action, String ts) {
}
