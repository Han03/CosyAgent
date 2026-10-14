package com.cosy.knowledge.common;

/**
 * 统一响应包装（与 cosy-agent 的 Result 同构：code=0 成功，非 0 失败）。
 *
 * @param code    业务码（0=成功）
 * @param message 提示信息
 * @param data    负载
 * @param <T>     负载类型
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
