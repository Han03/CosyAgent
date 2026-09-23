package com.cosy.agent.common.exception;

import com.cosy.agent.common.enums.ErrorCode;

/**
 * 业务异常：携带错误码，由全局异常处理器统一转换为响应。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.code = errorCode.code();
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.message() + ": " + detail);
        this.code = errorCode.code();
    }

    public int code() {
        return code;
    }
}
