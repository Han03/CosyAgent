/**
 * 统一响应协议：所有 REST 接口返回 {@link com.cosy.agent.common.api.Result}。
 *
 * <p>结构：{@code code / message / data}；成功 code=0，失败用
 * {@link com.cosy.agent.common.enums.ErrorCode} 枚举。业务异常走
 * {@link com.cosy.agent.common.exception.BizException}，由
 * {@link com.cosy.agent.common.exception.GlobalExceptionHandler} 统一转成 Result。</p>
 */
package com.cosy.agent.common;
