package com.travelplus.agent.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【企业级客服Agent的实现】会话状态存储（LangGraph Checkpointer 的轻量实现）。
 * <p>
 * 实现细节：
 * - Redis Hash 保存 support:state:{userId}：mode（AI/WAITING/HUMAN）、ticketId、intent、
 *   turnCount、lastRunId、updatedAt；TTL 取 handoffTtlHours；
 * - 状态是「AI 是否自动回复」的唯一开关：转人工后 AI 静默，人工结束后恢复；
 * - Redis 不可用时降级进程内 Map，保证客服主链路不被治理组件拖垮；
 * - 与 ChatMemoryStore 分工：ChatMemory 存问答上下文，本类存业务状态（谁在服务、意图、轮次）。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class SupportSessionStore {

    /** AI 自动服务 */
    public static final String MODE_AI = "AI";
    /** 已转人工，排队等待接入 */
    public static final String MODE_WAITING = "WAITING";
    /** 人工客服服务中 */
    public static final String MODE_HUMAN = "HUMAN";

    private static final String KEY_PREFIX = "support:state:";

    private final StringRedisTemplate redis;
    private final SupportProperties props;

    /** Redis 不可用时的进程内降级状态 */
    private final Map<Long, Map<String, String>> local = new ConcurrentHashMap<>();

    public SupportSessionStore(StringRedisTemplate redis, SupportProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 客服 Agent 记忆会话 id（与 SupportServiceImpl/人工客服回复共用同一键，
     * 保证人工阶段和 AI 阶段的上下文连续）。
     */
    public static String memorySessionId(Long userId) {
        return "support-" + (userId == null ? "anonymous" : userId);
    }

    /** 读取用户会话状态；无记录默认 AI */
    public SessionState load(Long userId) {
        if (userId == null) {
            return SessionState.initial();
        }
        Map<String, String> map;
        try {
            map = redis.opsForHash().entries(KEY_PREFIX + userId).entrySet().stream()
                    .collect(HashMap::new, (m, e) -> m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue())), HashMap::putAll);
        } catch (Exception e) {
            log.warn("【客服状态】Redis 不可用，读进程内状态: {}", e.getMessage());
            map = local.getOrDefault(userId, Map.of());
        }
        if (map == null || map.isEmpty()) {
            return SessionState.initial();
        }
        long ticketId = parseLong(map.get("ticketId"));
        int turnCount = (int) parseLong(map.get("turnCount"));
        return new SessionState(
                map.getOrDefault("mode", MODE_AI),
                ticketId > 0 ? ticketId : null,
                map.getOrDefault("intent", ""),
                turnCount,
                map.getOrDefault("lastRunId", ""),
                map.getOrDefault("updatedAt", ""));
    }

    /** 保存状态（写 Redis Hash + 续期；失败降级内存） */
    public void save(Long userId, SessionState state) {
        if (userId == null) {
            return;
        }
        Map<String, String> map = new HashMap<>();
        map.put("mode", state.mode());
        map.put("ticketId", state.ticketId() == null ? "" : String.valueOf(state.ticketId()));
        map.put("intent", state.intent() == null ? "" : state.intent());
        map.put("turnCount", String.valueOf(state.turnCount()));
        map.put("lastRunId", state.lastRunId() == null ? "" : state.lastRunId());
        map.put("updatedAt", LocalDateTime.now().toString());
        try {
            String key = KEY_PREFIX + userId;
            redis.opsForHash().putAll(key, map);
            redis.expire(key, Duration.ofHours(props.getHandoffTtlHours()));
        } catch (Exception e) {
            log.warn("【客服状态】Redis 不可用，写进程内状态: {}", e.getMessage());
            local.put(userId, map);
        }
    }

    /** 标记转人工（WAITING=排队，HUMAN=人工服务中） */
    public void markHandoff(Long userId, Long ticketId, String mode, String intent) {
        SessionState current = load(userId);
        save(userId, new SessionState(mode, ticketId, intent, current.turnCount(),
                current.lastRunId(), current.updatedAt()));
    }

    /** 人工结束，恢复 AI */
    public void resetToAi(Long userId, String intent) {
        SessionState current = load(userId);
        save(userId, new SessionState(MODE_AI, null, intent, current.turnCount(),
                current.lastRunId(), current.updatedAt()));
    }

    /** 清空状态（管理员/会话重置） */
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("【客服状态】Redis 删除失败: {}", e.getMessage());
        }
        local.remove(userId);
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 会话状态快照：mode=AI/WAITING/HUMAN；ticketId=当前人工工单；turnCount=累计轮次 */
    public record SessionState(String mode, Long ticketId, String intent, int turnCount,
                               String lastRunId, String updatedAt) {

        public static SessionState initial() {
            return new SessionState(MODE_AI, null, "", 0, "", "");
        }

        /** 完成一轮对话后的状态更新 */
        public SessionState next(String newIntent, String newRunId) {
            return new SessionState(mode, ticketId, newIntent, turnCount + 1, newRunId,
                    LocalDateTime.now().toString());
        }
    }
}
