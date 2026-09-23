package com.cosy.agent.agent.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 容错支持（Step 5）：对四类外部调用（LLM/工具/记忆/知识检索）统一施加
 * Resilience4j 组合策略 —— Retry → CircuitBreaker → RateLimiter → Bulkhead → TimeLimiter（外→内）。
 *
 * <p>实例按命名约定从各 Registry 查找（{@code <target>-retry / -cb / -ratelimit / -bulkhead / -timelimiter}），
 * 未配置的组件自动跳过（默认无容错，行为与 Step 4 前一致）；策略参数全部由
 * resilience4j.* YAML（环境变量可覆盖）声明式管理，指标经 Actuator 暴露。</p>
 */
@Component
public class ResilienceSupport implements DisposableBean {

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final ExecutorService executor = Executors.newFixedThreadPool(8,
            runnable -> {
                Thread thread = new Thread(runnable, "cosy-resilience-tl");
                thread.setDaemon(true);
                return thread;
            });

    public ResilienceSupport(RetryRegistry retryRegistry, CircuitBreakerRegistry circuitBreakerRegistry,
                             RateLimiterRegistry rateLimiterRegistry, TimeLimiterRegistry timeLimiterRegistry,
                             BulkheadRegistry bulkheadRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
    }

    public <T> T execute(ResilienceTarget target, Supplier<T> supplier) {
        return execute(target, supplier, true);
    }

    /** @param retryEnabled 是否启用重试（工具按幂等性决定，见 AgentTool.retryable） */
    public <T> T execute(ResilienceTarget target, Supplier<T> supplier, boolean retryEnabled) {
        String prefix = target.name().toLowerCase(Locale.ROOT);
        Supplier<T> chain = supplier;

        Retry retry = retryEnabled ? findRetry(prefix + "-retry") : null;
        if (retry != null) {
            chain = Retry.decorateSupplier(retry, chain);
        }
        CircuitBreaker breaker = findBreaker(prefix + "-cb");
        if (breaker != null) {
            chain = CircuitBreaker.decorateSupplier(breaker, chain);
        }
        RateLimiter limiter = findLimiter(prefix + "-ratelimit");
        if (limiter != null) {
            chain = RateLimiter.decorateSupplier(limiter, chain);
        }
        Bulkhead bulkhead = findBulkhead(prefix + "-bulkhead");
        if (bulkhead != null) {
            chain = Bulkhead.decorateSupplier(bulkhead, chain);
        }
        TimeLimiter timeLimiter = findTimeLimiter(prefix + "-timelimiter");
        if (timeLimiter != null) {
            chain = withTimeLimiter(timeLimiter, chain);
        }
        return chain.get();
    }

    private <T> Supplier<T> withTimeLimiter(TimeLimiter timeLimiter, Supplier<T> inner) {
        Callable<T> callable = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> executor.submit(() -> inner.get()));
        return () -> {
            try {
                return callable.call();
            } catch (Exception e) {
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        };
    }

    /**
     * 按名解析实例：仅当该名称已注册配置（yaml / of(Map)）时才返回实例，
     * 未注册返回 null（跳过该组件）——保证"未配置即无容错"的声明式语义。
     * 注意：resilience4j 2.x 中 {@code instance(name)} 单参重载一律返回<b>默认配置</b>实例
     * 且 {@code getConfiguration(name)} 为空时也会创建默认实例，因此必须显式按配置构造。
     */
    private Retry findRetry(String name) {
        return retryRegistry.getConfiguration(name)
                .map(config -> retryRegistry.retry(name, config))
                .orElse(null);
    }

    private CircuitBreaker findBreaker(String name) {
        return circuitBreakerRegistry.getConfiguration(name)
                .map(config -> circuitBreakerRegistry.circuitBreaker(name, config))
                .orElse(null);
    }

    private RateLimiter findLimiter(String name) {
        return rateLimiterRegistry.getConfiguration(name)
                .map(config -> rateLimiterRegistry.rateLimiter(name, config))
                .orElse(null);
    }

    private Bulkhead findBulkhead(String name) {
        return bulkheadRegistry.getConfiguration(name)
                .map(config -> bulkheadRegistry.bulkhead(name, config))
                .orElse(null);
    }

    private TimeLimiter findTimeLimiter(String name) {
        return timeLimiterRegistry.getConfiguration(name)
                .map(config -> timeLimiterRegistry.timeLimiter(name, config))
                .orElse(null);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
