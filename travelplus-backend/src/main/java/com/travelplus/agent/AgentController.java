package com.travelplus.agent;

import com.travelplus.agent.core.AgentOrchestrator;
import com.travelplus.agent.core.ChatMemoryStore;
import com.travelplus.agent.core.PromptRegistry;
import com.travelplus.agent.core.SseSink;
import com.travelplus.agent.core.ToolRegistry;
import com.travelplus.agent.guard.AgentRateLimiter;
import com.travelplus.agent.observability.AgentMetrics;
import com.travelplus.agent.resilience.LlmCircuitBreaker;
import com.travelplus.config.AiProperties;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.utils.UserHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 【Agent架构升级的实现】旅游 Agent 入口控制器（薄 HTTP 层：协议 → 护栏 → 委托编排器）。
 * <p>
 * 实现细节（与旧版巨型 AiChatController 的架构差异）：
 * - Controller 只做四件事：输入护栏 → 会话解析 → 限流 → 提交编排器；全部 Agent 逻辑
 *   下沉到 agent 包各组件（编排/工具/记忆/熔断/观测），可单测、可复用、可替换。
 * - 新协议：POST /ai/chat 请求体 {"sessionId?","message"}——历史由服务端会话记忆持有，
 *   请求体积恒定；兼容旧 {"messages":[...]}（取最后一条 user 消息）平滑过渡。
 * - 输入护栏：控制字符清洗 + 长度截断（MAX_MESSAGE_LEN）；空消息 400。
 * - 限流：登录按 userId、匿名按 IP 的 Redis 固定窗口（AgentRateLimiter），
 *   超限抛 429 + retryAfter 背压语义（HTTP 状态码先于 SSE 开流，前端可直接识别）。
 * - SSE v2 结构化协议（见 SseSink）：meta/delta/tool/error/done 五类事件。
 * - 观测端点：GET /ai/history（会话续连回显）、GET /ai/metrics（运行指标快照）。
 * 测试：curl -N -X POST http://localhost:8081/ai/chat -H "Content-Type: application/json"
 *       -H "authorization: <token>" -d '{"message":"我的行程有哪些"}'
 *
 * @author TravelPlus
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AgentController {

    /** 单条消息长度上限 */
    private static final int MAX_MESSAGE_LEN = 2000;

    /** 控制字符清洗（除 \t \n \r 外的全部 C0 控制符 + DEL） */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");

    @Resource
    private AgentOrchestrator orchestrator;

    @Resource
    private ChatMemoryStore memoryStore;

    @Resource
    private AgentRateLimiter rateLimiter;

    @Resource
    private AgentMetrics metrics;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private LlmCircuitBreaker breaker;

    @Resource
    private ObjectMapper objectMapper;

    /** 复用客服 AI 专用线程池（同为 DeepSeek IO 调用场景，线程池隔离于 Tomcat 请求线程） */
    @Resource
    @Qualifier("supportTaskExecutor")
    private ThreadPoolTaskExecutor aiExecutor;

    @Resource
    private AiProperties aiProperties;

    @Resource
    private PromptRegistry promptRegistry;

    /** 追问建议生成专用客户端（独立轻通道：非流式、零工具，不复用编排器——见 /followups 注释） */
    private final HttpClient followupClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * SSE 流式对话接口（匿名可浏览，带 token 时 Agent 可操作用户行程）
     *
     * @param body    {"sessionId?": 会话id（不传则服务端签发）, "message": 本轮提问}
     * @param request 请求对象（匿名限流取客户端 IP）
     * @return SseEmitter 流式 emitter
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        /* 1. 输入护栏：取 message（兼容旧 messages 协议）→ 清洗控制字符 → 长度截断。
         *    错误走 SSE error 事件而非抛异常——全局 WebExceptionAdvice 会把 RuntimeException
         *    统一转为 HTTP 200 + Result.fail，会吞掉 4xx 语义，且 SSE 客户端按协议收敛更一致。 */
        String message = extractMessage(body);
        if (message.isBlank()) {
            return rejectInline(emitter, "INVALID_INPUT", "消息不能为空");
        }
        /* 2. 会话解析：客户端携带合法 UUID 则续连，否则服务端签发（防伪造 key 注入他人会话） */
        String providedSessionId = body.get("sessionId") == null ? "" : String.valueOf(body.get("sessionId")).trim();
        final String sessionId = ChatMemoryStore.isValidSessionId(providedSessionId)
                ? providedSessionId : ChatMemoryStore.newSessionId();
        /* 3. 限流（用户/IP 维度）：超限在 SSE 流内发 RATE_LIMITED error 事件（含等待秒数背压语义） */
        UserDTO me = UserHolder.getUser();
        String dimension = me != null ? "u:" + me.getId() : "ip:" + clientIp(request);
        AgentRateLimiter.Decision decision = rateLimiter.check(dimension);
        if (!decision.allowed()) {
            metrics.incRateLimited();
            log.info("Agent 限流触发 dim={} count={}", dimension, decision.count());
            return rejectInline(emitter, "RATE_LIMITED",
                    "请求太频繁了，请 " + decision.retryAfterSeconds() + " 秒后再试");
        }
        /* 4. 异步编排（请求线程捕获登录态显式传给 AI 线程） */
        aiExecutor.execute(() -> orchestrator.run(sessionId, message, me, new SseSink(emitter, objectMapper)));
        return emitter;
    }

    /** 拒绝路径：不开 LLM 会话，直接在 SSE 流内发结构化错误并收敛（done() 内部会 complete 关流） */
    private SseEmitter rejectInline(SseEmitter emitter, String code, String message) {
        SseSink sink = new SseSink(emitter, objectMapper);
        sink.error(code, message);
        sink.done();
        return emitter;
    }

    /**
     * 【Agent架构升级的实现】会话续连：按 sessionId 回显服务端记忆的历史问答。
     * 前端换设备/刷新页面后据此还原对话（服务端持有记忆的配套端点）。
     */
    @GetMapping("/history")
    public Result history(@RequestParam("sessionId") String sessionId) {
        if (!ChatMemoryStore.isValidSessionId(sessionId)) {
            /* 不抛异常：全局 WebExceptionAdvice 会把异常包装成"服务器异常:..."，直接返回业务失败 */
            return Result.fail("非法会话 id");
        }
        return Result.ok(memoryStore.load(sessionId));
    }

    /**
     * 【Agent架构升级的实现】运行指标快照（演示期观测端点；生产化替换为 Micrometer/Prometheus）。
     */
    @GetMapping("/metrics")
    public Result metrics() {
        Map<String, Object> m = new LinkedHashMap<>(metrics.snapshot());
        m.put("toolsRegistered", toolRegistry.toolCount());
        m.put("breakerOpen", breaker.isOpen());
        return Result.ok(m);
    }

    /**
     * 【Agent架构升级的实现】追问建议生成：回答完成后基于对话历史生成 3 个"用户最可能追问"的
     * 短问题，前端据此把快捷提问 chips 从固定 4 条更新为上下文相关的动态追问。
     * <p>
     * 实现细节（为何不走编排器）：
     * - 零工具纯文本生成：AgentProfile.allowedTools 空集语义是"不限制"（回退全量工具 schema），
     *   无法表达"无工具"；且编排器会写会话记忆——追问生成必须只读主会话、不污染对话历史；
     * - 因此独立轻通道：非流式单次调用（stream=false，max_tokens=120 省预算），复用熔断器
     *   （熔断打开直接 fail，前端静默保留旧 chips）与指标计数；
     * - 上下文：主会话记忆只读最近 6 条（每条截 200 字），随本次 POST 一次性进 prompt；
     * - 输出解析：模型按提示词模板返回 JSON 数组；提取 [首..尾] 防御性解析，逐条校验
     *   （非空/去重/≤20字），解析失败返回空列表——前端保持旧 chips 不闪动。
     */
    @PostMapping("/followups")
    public Result followups(@RequestBody Map<String, Object> body) {
        String sessionId = body.get("sessionId") == null ? "" : String.valueOf(body.get("sessionId")).trim();
        if (!ChatMemoryStore.isValidSessionId(sessionId)) {
            return Result.fail("非法会话 id");
        }
        if (!breaker.tryAcquire()) {
            return Result.fail("AI 服务繁忙，请稍后再试");
        }
        try {
            /* 主会话记忆只读：最近 6 条作为生成上下文 */
            List<Map<String, String>> history = memoryStore.load(sessionId);
            StringBuilder dialog = new StringBuilder();
            int from = Math.max(0, history.size() - 6);
            for (int i = from; i < history.size(); i++) {
                Map<String, String> m = history.get(i);
                String content = m.get("content");
                content = content.length() > 200 ? content.substring(0, 200) + "…" : content;
                dialog.append("user".equals(m.get("role")) ? "用户：" : "助手：").append(content).append('\n');
            }
            if (dialog.isEmpty()) {
                return Result.ok(List.of());
            }
            metrics.incLlmCalls();
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", aiProperties.getModel());
            req.put("stream", false);
            req.put("temperature", 0.8);
            /* deepseek-v4-flash 为混合推理模型：非流式调用会先产出 reasoning token（实测一次 83），
             * max_tokens 必须给足余量——此前设 120 时 reasoning 吃光预算导致正文 content 为空 */
            req.put("max_tokens", 512);
            ArrayNode msgs = req.putArray("messages");
            msgs.addObject().put("role", "system").put("content", promptRegistry.get(PromptRegistry.TRAVEL_FOLLOWUPS));
            msgs.addObject().put("role", "user").put("content",
                    "对话历史：\n" + dialog + "\n请输出追问建议 JSON 数组：");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiProperties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = followupClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                breaker.onFailure();
                log.warn("追问建议 LLM 调用失败 status={}", response.statusCode());
                return Result.fail("AI 服务繁忙，请稍后再试");
            }
            breaker.onSuccess();
            String content = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            return Result.ok(parseFollowups(content));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("追问建议生成失败: {}", e.getMessage());
            return Result.fail("AI 服务繁忙，请稍后再试");
        }
    }

    /** 追问 JSON 解析：提取 [首..尾] 防御代码块标记，逐条非空/去重/≤20 字，最多 3 条；失败返回空列表 */
    private List<String> parseFollowups(String content) {
        List<String> out = new ArrayList<>();
        try {
            int l = content.indexOf('[');
            int r = content.lastIndexOf(']');
            if (l < 0 || r <= l) {
                return out;
            }
            JsonNode arr = objectMapper.readTree(content.substring(l, r + 1));
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                String q = n.asText("").trim();
                if (!q.isEmpty() && !out.contains(q)) {
                    out.add(q.length() > 20 ? q.substring(0, 20) : q);
                }
                if (out.size() >= 3) {
                    break;
                }
            }
        } catch (Exception ignore) {
            // 模型未按格式输出 → 空列表，前端保留旧 chips
        }
        return out;
    }

    /** 提取本轮消息：优先 body.message；兼容旧协议 messages 数组取最后一条 user 消息 */
    private String extractMessage(Map<String, Object> body) {
        Object message = body.get("message");
        if (message instanceof String s && !s.isBlank()) {
            return clean(s);
        }
        if (body.get("messages") instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) instanceof Map<?, ?> m && "user".equals(String.valueOf(m.get("role")))) {
                    Object c = m.get("content");
                    if (c != null && !String.valueOf(c).isBlank()) {
                        return clean(String.valueOf(c));
                    }
                }
            }
        }
        return "";
    }

    /** 输入清洗：去控制字符、trim、长度截断 */
    private String clean(String s) {
        String out = CONTROL_CHARS.matcher(s).replaceAll(" ").trim();
        return out.length() > MAX_MESSAGE_LEN ? out.substring(0, MAX_MESSAGE_LEN) : out;
    }

    /** 客户端 IP（反代头优先，匿名限流维度） */
    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
