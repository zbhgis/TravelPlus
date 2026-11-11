package com.travelplus.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelplus.agent.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 【Agent架构升级的实现】Agent 服务端会话记忆（对标 Spring AI ChatMemoryRepository 的可插拔存储）。
 * <p>
 * 实现细节：
 - 存储模型：Redis List（key=agent:session:{sessionId}:msgs），每条消息一行 JSON（role/content）；
 *   RPUSH 追加 + LTRIM 只保留最近 memoryMaxMessages*2 条 + 滑动 EXPIRE（每次写入续期 memoryTtlHours）。
 * - 记忆范围：只持久化 user/assistant 两类消息（工具调用的中间 assistant/tool 消息不落记忆）——
 *   「memory 在工具循环外」是 Spring AI 的默认语义：记忆里永远只有完整的问答对，
 *   token 有界、跨轮重放语义干净；本轮工具中间消息在编排器的消息管线里即时组装。
 * - 架构意义：历史从「前端全量携带」改为「服务端持有」——多端续连（换设备接上对话）、
 *   防客户端伪造历史注入、单轮请求体积恒定（不随对话轮数增长）。
 * - 降级：Redis 不可用时退化为进程内 ConcurrentHashMap 窗口（单机语义），功能不中断。
 * - 安全：sessionId 只接受 UUID 格式（isValidSessionId），防止客户端伪造 key 探测/注入他人会话。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class ChatMemoryStore {

    /** sessionId 合法格式：UUID（36 位）及其子集 */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    private final StringRedisTemplate redis;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    /** Redis 不可用时的进程内降级存储 */
    private final Map<String, Deque<String>> localMemory = new ConcurrentHashMap<>();

    public ChatMemoryStore(StringRedisTemplate redis, AgentProperties props, ObjectMapper objectMapper) {
        this.redis = redis;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** 客户端上报的 sessionId 是否合法（仅 UUID 格式） */
    public static boolean isValidSessionId(String id) {
        return id != null && SESSION_ID_PATTERN.matcher(id).matches();
    }

    /** 服务端签发新会话 id */
    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    private String key(String sessionId) {
        return "agent:session:" + sessionId + ":msgs";
    }

    /**
     * 追加一条会话消息（RPUSH + LTRIM 窗口 + 滑动 TTL；Redis 失败降级内存队列）
     *
     * @param sessionId 会话 id
     * @param role      user / assistant
     * @param content   消息正文
     */
    public void append(String sessionId, String role, String content) {
        String line;
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("role", role);
            n.put("content", content == null ? "" : content);
            line = n.toString();
        } catch (Exception e) {
            return;
        }
        try {
            String k = key(sessionId);
            redis.opsForList().rightPush(k, line);
            redis.opsForList().trim(k, -props.getMemoryMaxMessages() * 2L, -1);
            redis.expire(k, Duration.ofHours(props.getMemoryTtlHours()));
        } catch (Exception e) {
            log.warn("【Agent记忆】Redis 不可用，降级进程内会话: {}", e.getMessage());
            Deque<String> dq = localMemory.computeIfAbsent(sessionId, x -> new ArrayDeque<>());
            dq.addLast(line);
            while (dq.size() > props.getMemoryMaxMessages() * 2L) {
                dq.removeFirst();
            }
        }
    }

    /**
     * 【客服Agent升级的实现】清空会话记忆（客服页「清空对话」时同步重置模型上下文）。
     * <p>
     * 实现细节：删除 Redis 会话 key + 移除进程内降级副本；Redis 故障时仅清本地，
     * 不向上抛异常——清空失败不应阻断用户操作。
     *
     * @param sessionId 会话 id
     */
    public void clear(String sessionId) {
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("【Agent记忆】Redis 清空失败，仅清理进程内会话: {}", e.getMessage());
        }
        localMemory.remove(sessionId);
    }

    /**
     * 读取会话记忆（仅 user/assistant 问答对，最旧的在前）。
     * 用于编排器构建消息管线与 GET /ai/history 会话续连回显。
     */
    public List<Map<String, String>> load(String sessionId) {
        List<String> lines;
        try {
            lines = redis.opsForList().range(key(sessionId), 0, -1);
        } catch (Exception e) {
            log.warn("【Agent记忆】Redis 不可用，读进程内会话: {}", e.getMessage());
            Deque<String> dq = localMemory.get(sessionId);
            lines = dq == null ? List.of() : new ArrayList<>(dq);
        }
        List<Map<String, String>> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            try {
                JsonNode n = objectMapper.readTree(line);
                String role = n.path("role").asText("");
                String content = n.path("content").asText("");
                if (("user".equals(role) || "assistant".equals(role)) && !content.isBlank()) {
                    out.add(Map.of("role", role, "content", content));
                }
            } catch (Exception ignore) {
                // 单行损坏跳过，不影响整体
            }
        }
        return out;
    }
}
