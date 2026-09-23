package com.cosy.agent;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

/**
 * 测试助手：构造"默认注册表"的 ResilienceSupport。
 * 未命名实例按默认配置创建（或无配置时跳过），单测中行为与无容错等价（快速成功/失败），
 * 供各单测以最小侵入注入构造参数。
 */
public final class TestResilience {

    private TestResilience() {
    }

    public static ResilienceSupport defaultResilience() {
        return new ResilienceSupport(RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
    }
}
