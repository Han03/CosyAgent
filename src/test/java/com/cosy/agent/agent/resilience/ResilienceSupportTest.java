package com.cosy.agent.agent.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ResilienceSupport 组合策略测试（Step 5）：
 * 重试（瞬时故障自愈）、熔断（打开后快速失败）、限流（超限拒绝）、
 * 超时（慢调用中断）、舱壁（并发隔离）五条策略各自生效。
 */
class ResilienceSupportTest {

    @Test
    void retriesTransientFailuresUntilSuccess() {
        RetryRegistry retries = RetryRegistry.of(Map.of("llm-retry",
                RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ZERO).build()));
        ResilienceSupport support = support(retries, CircuitBreakerRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());

        AtomicInteger calls = new AtomicInteger();
        String result = support.execute(ResilienceTarget.LLM, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void opensCircuitBreakerAndFailsFast() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(Map.of("llm-cb",
                CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30)).build()));
        ResilienceSupport support = support(RetryRegistry.ofDefaults(), breakers,
                RateLimiterRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());

        Supplier<String> failing = () -> { throw new RuntimeException("down"); };
        assertThatThrownBy(() -> support.execute(ResilienceTarget.LLM, failing))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> support.execute(ResilienceTarget.LLM, failing))
                .isInstanceOf(RuntimeException.class);

        // 窗口已满且失败率超阈值 → 熔断打开，第三次直接 CallNotPermittedException（快速失败，不再调用）
        assertThatThrownBy(() -> support.execute(ResilienceTarget.LLM, failing))
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void rejectsWhenRateLimitExceeded() throws Exception {
        // RateLimiter 语义为"周期内并发许可"：decorateSupplier 在调用完成后即释放许可，
        // 顺序快调用不会触发拒绝；用并发持锁调用验证"超限拒绝"。
        RateLimiterRegistry limiters = RateLimiterRegistry.of(Map.of("llm-ratelimit",
                RateLimiterConfig.custom().limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO).build()));
        ResilienceSupport support = support(RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(),
                limiters, TimeLimiterRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());

        CountDownLatch enter = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> support.execute(ResilienceTarget.LLM, () -> {
            enter.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "held";
        }));

        enter.await(3, TimeUnit.SECONDS);
        assertThatThrownBy(() -> support.execute(ResilienceTarget.LLM, () -> "second"))
                .isInstanceOf(RequestNotPermitted.class);
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    void timesOutSlowCalls() {
        TimeLimiterRegistry limiters = TimeLimiterRegistry.of(Map.of("llm-timelimiter",
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(50)).build()));
        ResilienceSupport support = support(RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults(), limiters, BulkheadRegistry.ofDefaults());

        assertThatThrownBy(() -> support.execute(ResilienceTarget.LLM, () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "late";
        })).satisfies(exception -> assertThat(exception).hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class));
    }

    @Test
    void isolatesConcurrencyWithBulkhead() throws Exception {
        BulkheadRegistry bulkheads = BulkheadRegistry.of(Map.of("llm-bulkhead",
                BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()));
        ResilienceSupport support = support(RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), bulkheads);

        CountDownLatch enter = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> support.execute(ResilienceTarget.LLM, () -> {
            enter.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "held";
        }));

        enter.await(3, TimeUnit.SECONDS);
        assertThatThrownBy(() -> support.execute(ResilienceTarget.LLM, () -> "blocked"))
                .isInstanceOf(BulkheadFullException.class);
        release.countDown();
        executor.shutdownNow();
    }

    private ResilienceSupport support(RetryRegistry retries, CircuitBreakerRegistry breakers,
                                      RateLimiterRegistry limiters, TimeLimiterRegistry timeLimiters,
                                      BulkheadRegistry bulkheads) {
        return new ResilienceSupport(retries, breakers, limiters, timeLimiters, bulkheads);
    }
}
