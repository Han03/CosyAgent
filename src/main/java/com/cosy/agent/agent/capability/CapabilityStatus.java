package com.cosy.agent.agent.capability;

/** 能力实例健康状态（AP 心跳摘除 / CP 探测标记）。 */
public enum CapabilityStatus {
    /** 正常可用（注册后默认；心跳续约保持） */
    UP,
    /** 可疑：AP 心跳超时未续约，等待摘除窗口 */
    SUSPECT,
    /** 不可用：调用失败 / CP 主动探测失败；resolve 时跳过 */
    DOWN,
    /** 未知：启动加载未完成或提供者状态缺失 */
    UNKNOWN;

    public boolean callable() {
        return this == UP;
    }
}
