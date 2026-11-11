package com.travelplus.agent.resilience;

import com.travelplus.agent.AgentProperties;
import com.travelplus.agent.observability.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【Agent架构升级的实现】LLM 调用熔断器（CLOSED → OPEN → HALF_OPEN 三态）。
 * <p>
 * 实现细节（生产清单的 "health & circuit breakers: trip on dependency failures, shed load gracefully"）：
 * - CLOSED：正常放行；连续失败 ≥ breakerFailureThreshold 次（网络异常/HTTP 5xx/429 计失败）
 *   → 切 OPEN（DeepSeek 宕机/限流风暴时不再每轮烧 90s 超时）。
 * - OPEN：直接拒绝（tryAcquire=false），编排器立即给用户降级文案并收敛——
 *   故障期间快速失败而不是排队等待，同时给上游留恢复窗口。
 * - HALF_OPEN：openSeconds 到期后放行一个探测请求（AtomicBoolean 单探针，并发只放一个），
 *   成功→CLOSED 复位计数；失败→重新 OPEN 并刷新计时。
 * - 线程安全：状态迁移用 synchronized 保护（低频路径），探测单放行用 CAS。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class LlmCircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final AgentProperties props;
    private final AgentMetrics metrics;

    private State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAt;
    private final AtomicBoolean probeActive = new AtomicBoolean(false);

    public LlmCircuitBreaker(AgentProperties props, AgentMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    /**
     * 尝试获取一次 LLM 调用许可。
     * @return true=放行（CLOSED 正常 / HALF_OPEN 探测单放行）；false=熔断中直接降级
     */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (System.currentTimeMillis() - openedAt >= props.getBreakerOpenSeconds() * 1000L) {
                    state = State.HALF_OPEN;
                    log.info("【Agent熔断】冷却期满，进入半开探测");
                    return probeActive.compareAndSet(false, true);
                }
                return false;
            }
            case HALF_OPEN -> {
                /* 半开期间只放行一个探测请求，其余直接拒绝 */
                return probeActive.compareAndSet(false, true);
            }
        }
        return false;
    }

    /** 调用成功（含流式正常读完）：复位计数、回到 CLOSED */
    public void onSuccess() {
        consecutiveFailures.set(0);
        probeActive.set(false);
        synchronized (this) {
            if (state != State.CLOSED) {
                log.info("【Agent熔断】探测成功，恢复 CLOSED");
            }
            state = State.CLOSED;
        }
    }

    /** 调用失败：累计计数，达到阈值打开熔断 */
    public void onFailure() {
        probeActive.set(false);
        int fails = consecutiveFailures.incrementAndGet();
        synchronized (this) {
            if (state == State.HALF_OPEN || fails >= props.getBreakerFailureThreshold()) {
                if (state != State.OPEN) {
                    state = State.OPEN;
                    openedAt = System.currentTimeMillis();
                    metrics.incBreakerOpens();
                    log.warn("【Agent熔断】连续失败 {} 次，熔断打开 {}s", fails, props.getBreakerOpenSeconds());
                }
            }
        }
    }

    /** 当前是否处于熔断打开状态（供观测端点） */
    public synchronized boolean isOpen() {
        return state == State.OPEN;
    }
}
