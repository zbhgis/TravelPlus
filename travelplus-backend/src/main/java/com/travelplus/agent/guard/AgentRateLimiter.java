package com.travelplus.agent.guard;

import com.travelplus.agent.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【Agent架构升级的实现】Agent 对话限流器：Redis 固定窗口计数，按「用户/IP」维度。
 * <p>
 * 实现细节（生产清单的 "rate limiting and backpressure: per-tenant limits, surface try-later semantics"）：
 * - 维度键：登录用户 "u:{userId}"，匿名 "ip:{clientIp}"——防单账号/单机刷爆 DeepSeek 配额；
 * - 固定窗口：key=agent:rl:{dim}:{窗口起点}，INCR 计数、首个请求补 EXPIRE（窗口 2 倍时长兜底），
 *   超限返回 allowed=false + retryAfter 秒（背压语义：告诉客户端何时可以重试）；
 * - Redis 不可用时降级为单机内存窗口（ConcurrentHashMap），保底限流语义不丢——
 *   企业治理组件必须比被保护资源活得更久。
 * - 未复用全局 @RateLimiter 切面的原因：该切面 USER 维度恒为 anonymous（见 RateLimiterAspect），
 *   且 SSE 接口需要 retryAfter 数值，Agent 专用限流器更贴合。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class AgentRateLimiter {

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    /** 内存降级窗口：dim → [windowStart, count] */
    private final Map<String, long[]> localWindows = new ConcurrentHashMap<>();

    public AgentRateLimiter(StringRedisTemplate redis, AgentProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 检查本窗口是否放行（调用即计数）。
     *
     * @param dimensionKey 限流维度键（"u:1" / "ip:1.2.3.4"，由 Controller 组装）
     * @return Decision(allowed, retryAfterSeconds, 已用次数)
     */
    public Decision check(String dimensionKey) {
        return check(dimensionKey, props.getRateLimitPerMinute());
    }

    /**
     * 【企业级客服Agent的实现】按指定阈值限流：客服域与旅游助手域额度独立。
     *
     * @param dimensionKey   限流维度键
     * @param limitPerMinute 本域每分钟上限
     */
    public Decision check(String dimensionKey, int limitPerMinute) {
        long windowMs = props.getRateLimitWindowSeconds() * 1000L;
        long now = System.currentTimeMillis();
        long windowStart = now - now % windowMs;
        int retryAfter = (int) ((windowStart + windowMs - now) / 1000) + 1;
        try {
            String key = "agent:rl:" + dimensionKey + ":" + windowStart;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(props.getRateLimitWindowSeconds() * 2L));
            }
            long used = count == null ? 1 : count;
            return new Decision(used <= limitPerMinute, retryAfter, used);
        } catch (Exception e) {
            log.warn("【Agent限流】Redis 不可用，降级本地窗口: {}", e.getMessage());
            long[] w = localWindows.compute(dimensionKey, (k, old) ->
                    (old == null || old[0] != windowStart) ? new long[]{windowStart, 0} : old);
            long used = ++w[1];
            return new Decision(used <= limitPerMinute, retryAfter, used);
        }
    }

    /** 限流判定结果：allowed=是否放行；retryAfterSeconds=建议等待秒数；count=本窗口已用次数 */
    public record Decision(boolean allowed, int retryAfterSeconds, long count) {
    }
}
