/**
 * 安全层：API Key 校验。
 *
 * <p>{@link com.cosy.agent.security.ApiKeyFilter} 基于
 * {@link com.cosy.agent.config.SecurityProperties}（prefix {@code cosy.security}），
 * 校验 {@code X-Cosy-API-Key} 请求头；关闭时放行全部请求。</p>
 */
package com.cosy.agent.security;
