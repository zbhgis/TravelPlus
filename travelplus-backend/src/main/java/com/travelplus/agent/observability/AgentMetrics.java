package com.travelplus.agent.observability;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 【Agent架构升级的实现】Agent 运行指标（进程内计数器，无外部依赖）。
 * <p>
 * 实现细节：
 * - 覆盖生产清单关注的核心信号：调用量（runs/llmCalls）、工具健康（toolCalls/toolFailures）、
 *   治理触发（rateLimited/breakerOpens/llmRetries）、体验（degraded 会话、平均/最近耗时）；
 * - 高频计数用 LongAdder（并发优于 AtomicLong），快照聚合时再 sum；
 * - 经 GET /ai/metrics 暴露 JSON 快照，便于演示期观测与压测验证；
 *   生产化路径：替换为 Micrometer（Counter/Timer）接 Prometheus，接口结构不变。
 *
 * @author TravelPlus
 */
@Component
public class AgentMetrics {

    private final AtomicLong runsTotal = new AtomicLong();
    private final AtomicLong runsDegraded = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmRetries = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolFailures = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong breakerOpens = new AtomicLong();

    private final LongAdder totalRunMs = new LongAdder();
    private volatile long lastRunMs;

    public void incLlmCalls() {
        llmCalls.incrementAndGet();
    }

    public void incLlmRetries() {
        llmRetries.incrementAndGet();
    }

    public void incToolCalls() {
        toolCalls.incrementAndGet();
    }

    public void incToolFailures() {
        toolFailures.incrementAndGet();
    }

    public void incRateLimited() {
        rateLimited.incrementAndGet();
    }

    public void incBreakerOpens() {
        breakerOpens.incrementAndGet();
    }

    public void incRunsDegraded() {
        runsDegraded.incrementAndGet();
    }

    /** 记录一次完整对话运行（含耗时） */
    public void recordRun(long ms) {
        totalRunMs.add(ms);
        lastRunMs = ms;
        runsTotal.incrementAndGet();
    }

    /** 指标快照（/ai/metrics 响应体） */
    public Map<String, Object> snapshot() {
        long runs = runsTotal.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runsTotal", runs);
        m.put("runsDegraded", runsDegraded.get());
        m.put("avgRunMs", runs == 0 ? 0 : totalRunMs.sum() / runs);
        m.put("lastRunMs", lastRunMs);
        m.put("llmCalls", llmCalls.get());
        m.put("llmRetries", llmRetries.get());
        m.put("toolCalls", toolCalls.get());
        m.put("toolFailures", toolFailures.get());
        m.put("rateLimited", rateLimited.get());
        m.put("breakerOpens", breakerOpens.get());
        return m;
    }
}
