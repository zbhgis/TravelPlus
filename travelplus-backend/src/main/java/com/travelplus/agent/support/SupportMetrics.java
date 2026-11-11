package com.travelplus.agent.support;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 【企业级客服Agent的实现】客服域可观测指标。
 * <p>
 * 实现细节：
 * - 业务信号：运行量、各意图分布、转人工数、护栏拦截数、兜底数、用户反馈正负；
 * - 体验信号：平均/最近耗时；
 * - 与 AgentMetrics 分工：AgentMetrics 关注 LLM/工具/熔断等运行时，本类关注客服业务质量，
 *   由 GET /support/admin/metrics 暴露，后续可平滑替换为 Micrometer + Prometheus。
 *
 * @author TravelPlus
 */
@Component
public class SupportMetrics {

    private final LongAdder runs = new LongAdder();
    private final LongAdder guardBlocks = new LongAdder();
    private final LongAdder handoffs = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();
    private final LongAdder feedbackPositive = new LongAdder();
    private final LongAdder feedbackNegative = new LongAdder();
    private final LongAdder totalMs = new LongAdder();
    private final AtomicLong lastMs = new AtomicLong();

    /** 意图分布：intent → 次数 */
    private final Map<String, LongAdder> intents = new ConcurrentHashMap<>();

    public void recordRun(SupportIntent intent, long ms) {
        runs.increment();
        totalMs.add(ms);
        lastMs.set(ms);
        intents.computeIfAbsent(intent.name(), k -> new LongAdder()).increment();
    }

    public void incGuardBlocks() {
        guardBlocks.increment();
    }

    public void incHandoffs() {
        handoffs.increment();
    }

    public void incFallbacks() {
        fallbacks.increment();
    }

    public void incFeedback(boolean helpful) {
        if (helpful) {
            feedbackPositive.increment();
        } else {
            feedbackNegative.increment();
        }
    }

    /** 指标快照（后台工作台/演示观测） */
    public Map<String, Object> snapshot() {
        long runCount = runs.sum();
        Map<String, Long> intentCounts = new LinkedHashMap<>();
        intents.forEach((k, v) -> intentCounts.put(k, v.sum()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runsTotal", runCount);
        m.put("avgRunMs", runCount == 0 ? 0 : totalMs.sum() / runCount);
        m.put("lastRunMs", lastMs.get());
        m.put("intents", intentCounts);
        m.put("handoffs", handoffs.sum());
        m.put("guardBlocks", guardBlocks.sum());
        m.put("fallbacks", fallbacks.sum());
        m.put("feedbackPositive", feedbackPositive.sum());
        m.put("feedbackNegative", feedbackNegative.sum());
        return m;
    }
}
