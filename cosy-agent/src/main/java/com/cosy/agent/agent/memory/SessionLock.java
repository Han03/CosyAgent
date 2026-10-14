package com.cosy.agent.agent.memory;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 会话级分布式锁（P0 分布式演进：会话状态分布式化的并发前置）。
 *
 * <p>锁 key：{@code cosy:lock:session:{sessionId}}，基于 Redisson RLock
 * （Redis 单实例下为互斥锁；后续多实例部署时 RLock 自动具备看门狗续期语义）。
 * 用途：防止同一会话并发触发 ReAct 循环导致上下文错乱；不同会话锁互不干扰。</p>
 */
@Component
public class SessionLock {

    private static final String LOCK_PREFIX = "cosy:lock:session:";

    private final RedissonClient redisson;

    public SessionLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 非阻塞抢占：抢不到立即返回 false（调用方按并发冲突处理），避免排队放大延迟。 */
    public boolean tryLock(String sessionId) {
        RLock lock = redisson.getLock(LOCK_PREFIX + sessionId);
        try {
            return lock.tryLock(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放锁（仅当当前线程持有），锁超时自动过期，双保险防死锁。 */
    public void unlock(String sessionId) {
        RLock lock = redisson.getLock(LOCK_PREFIX + sessionId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
