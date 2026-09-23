package com.cosy.agent.common.enums;

/**
 * 业务错误码。
 */
public enum ErrorCode {

    SYSTEM_ERROR(500, "系统内部错误"),
    PARAM_INVALID(400, "参数校验失败"),

    AGENT_NOT_READY(10001, "智能体组件未就绪"),
    TOOL_NOT_FOUND(10002, "工具不存在"),
    TOOL_EXECUTE_FAILED(10003, "工具执行失败"),
    LLM_CALL_FAILED(10004, "模型调用失败"),
    MEMORY_ACCESS_FAILED(10005, "记忆访问失败"),
    VECTOR_SEARCH_FAILED(10006, "向量检索失败"),
    RATE_LIMITED(10007, "请求过于频繁，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
