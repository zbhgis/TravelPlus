package com.travelplus.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelplus.agent.AgentProperties;
import com.travelplus.agent.observability.AgentMetrics;
import com.travelplus.agent.resilience.LlmCircuitBreaker;
import com.travelplus.config.AiProperties;
import com.travelplus.dto.UserDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 【Agent架构升级的实现】Agent 编排器：对话运行的显式状态循环（对标 LangGraph 的状态机思想）。
 * <p>
 * 实现细节：
 * - 运行管线：meta 开流 → 会话记忆载入（服务端持有）→ 提示词模板 + 数据接地组装 system →
 *   进入「LLM 调用 → 有 tool_calls 则执行工具回喂 → 否则收敛」的有界循环 → done。
 * - 预算护栏（生产清单 hard caps）：maxRounds 轮上限 + maxToolCalls 工具总次数上限 +
 *   runTimeoutSeconds 墙钟死线，三重熔断防模型循环失控烧 token/钱。
 * - 熔断：每轮调用前 tryAcquire，熔断打开时立即类型化降级（LLM_UNAVAILABLE）快速失败，
 *   不再每轮苦等 90s 网络超时；调用结果回喂熔断器维持状态机。
 * - 重试：网络异常/5xx/429 按硬性上限重试（退避 500ms）；若流式正文已外发过 delta
 *   则不再重试（避免重复输出），直接降级收敛。
 * - 故障隔离：工具执行全权委托 ToolRegistry（异常收敛为结构化错误回喂模型）；
 *   编排器自身任何异常也收敛为 error 事件 + done，SSE 连接必收敛不悬挂。
 * - 可观测：runId 贯穿日志；LLM 调用/重试/工具/降级计数全部上报 AgentMetrics。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class AgentOrchestrator {

    @Resource
    private AiProperties aiProperties;

    @Resource
    private AgentProperties props;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private ChatMemoryStore memoryStore;

    @Resource
    private PromptRegistry promptRegistry;

    @Resource
    private ContextBuilder contextBuilder;

    @Resource
    private LlmCircuitBreaker breaker;

    @Resource
    private AgentMetrics metrics;

    /** Spring 容器 ObjectMapper：含 JSR310（行程 LocalDateTime 序列化）与非空过滤 */
    @Resource
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 【Agent架构升级·长度分层的实现】规划意图识别：命中即进入「规划档」长度层级。
     * 提示词模板里有静态的长度分层策略（普通档 300 字 / 规划档 600~1000 字），
     * 但 flash 档模型对静态规则遵循偏弱——这里在编排期对用户消息做确定性意图检测，
     * 命中后在 system 末尾动态追加规划模式指令，双保险保证规划类回答真正展开。
     */
    private static final java.util.regex.Pattern PLANNING_INTENT = java.util.regex.Pattern.compile(
            "规划|路线|行程|安排|攻略|几天|几日|一日游|两日|三日|四日|五日|周末|亲子|带老人|自驾|顺序|优化|绕路|预算");

    /** 规划档动态指令（追加到 system 末尾） */
    private static final String PLANNING_MODE_SUFFIX =
            "\n\n【本轮进入规划模式——长度分层策略·规划档】用户在要求规划行程/路线，这是你的核心价值场景：" +
            "回答应当展开为结构化长文（600~1000 字），按天或按上午/下午/晚上分段，" +
            "每天列出景点顺序与简要理由、交通衔接建议，结尾附 1~2 条预算或贴士。" +
            "此场景下长回答是正确的输出，不要压缩到普通档的 300 字。";

    /** 按用户意图追加长度分层指令：规划意图命中 → 规划档；否则保持提示词模板的普通档 */
    private String applyLengthPolicy(String systemPrompt, String userMessage) {
        if (userMessage == null || !PLANNING_INTENT.matcher(userMessage).find()) {
            return systemPrompt;
        }
        return systemPrompt + PLANNING_MODE_SUFFIX;
    }

    /** 单次 LLM 调用结果：failed 标记失败；toolCalls 非空表示模型请求工具 */
    private record LlmCall(boolean failed, String errorCode, String errorMessage, boolean retryable,
                           String content, ArrayNode toolCallsNode, List<ToolCall> toolCalls) {
    }

    /**
     * 【企业级客服Agent的实现】非流式运行结果：ok=true 时 content 为最终答复；
     * 否则 errorCode/errorMessage 为类型化失败原因。
     * runId/toolsUsed 供客服审计、指标与工作台排障使用。
     */
    public record RunResult(boolean ok, String content, String errorCode, String errorMessage,
                            String runId, List<String> toolsUsed) {

        public static RunResult success(String content, String runId, List<String> toolsUsed) {
            return new RunResult(true, content, null, null, runId, toolsUsed);
        }

        public static RunResult failure(String errorCode, String errorMessage,
                                        String runId, List<String> toolsUsed) {
            return new RunResult(false, null, errorCode, errorMessage, runId, toolsUsed);
        }
    }

    /** 模型请求的一次工具调用（分片拼接完成的完整形态） */
    private record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 运行一次旅游攻略 Agent 对话（SSE 流式；由 Controller 提交到 AI 线程池执行）。
     *
     * @param sessionId   服务端会话 id（记忆键）
     * @param userMessage 本轮用户消息
     * @param user        当前登录用户（可为 null，工具权限判定与 UserHolder 注入基于它）
     * @param sink        SSE 事件写出器
     */
    public void run(String sessionId, String userMessage, UserDTO user, AgentSink sink) {
        runWithProfile(sessionId, userMessage, user, sink, new AgentProfile(
                "travel", PromptRegistry.TRAVEL_ASSISTANT, true, contextBuilder::buildCityContext), true);
    }

    /**
     * 【客服Agent升级的实现】非流式运行：复用同一编排循环，事件写入静默接收器，返回最终答复文本。
     *
     * @param sessionId   服务端会话 id（客服按 userId 固定，实现多轮记忆）
     * @param userMessage 本轮用户消息
     * @param user        当前登录用户（客服工具查询订单/资产依赖其身份）
     * @param profile     运行档案（提示词 + 知识库接地 + 长度策略）
     * @return 运行结果；失败时由客服服务落兜底回复
     */
    public RunResult runToText(String sessionId, String userMessage, UserDTO user, AgentProfile profile) {
        return runWithProfile(sessionId, userMessage, user, new SilentSink(), profile, false);
    }

    /**
     * 【企业级客服Agent的实现】流式运行：事件直接写入调用方的 AgentSink（delta/tool/error），
     * 但 meta/done 生命周期由调用方统一管理——客服编排器需要在落库拿到消息 id 后再发 done，
     * 避免前端在消息尚未持久化时就开始拉取历史。
     *
     * @param sessionId   会话记忆键
     * @param userMessage 本轮消息
     * @param user        当前用户
     * @param sink        事件写出器（调用方已发 meta）
     * @param profile     运行档案（提示词 + 接地 + 工具白名单）
     * @return 运行结果（含 runId/工具列表，供审计）
     */
    public RunResult streamWithProfile(String sessionId, String userMessage, UserDTO user,
                                       AgentSink sink, AgentProfile profile) {
        return runWithProfile(sessionId, userMessage, user, sink, profile, false);
    }

    /**
     * 编排主流程（旅游/客服共用）：meta → 记忆 → 提示词 + 数据接地 → 有界工具循环 → 收敛。
     * emitLifecycle=true 时由本方法发 meta/done（旅游 SSE）；false 时由调用方管理生命周期（客服流式）。
     */
    private RunResult runWithProfile(String sessionId, String userMessage, UserDTO user,
                                     AgentSink sink, AgentProfile profile, boolean emitLifecycle) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        long t0 = System.currentTimeMillis();
        if (emitLifecycle) {
            sink.meta(runId, sessionId);
        }
        try {
            /* 1. 会话记忆：本轮用户消息落库 + 载入历史窗口（服务端持有，前端不再携带） */
            memoryStore.append(sessionId, "user", userMessage);
            List<Map<String, String>> history = memoryStore.load(sessionId);

            /* 2. system 组装：版本化提示词模板 + 日期 + 档案化数据接地
             *    （旅游=城市景点/游记，客服=平台知识库）+ 可选长度分层 */
            String systemPrompt = promptRegistry.get(profile.promptName())
                    + "\n\n今天是 " + LocalDate.now() + "。";
            String context = profile.contextProvider() == null ? "" : profile.contextProvider().apply(userMessage);
            if (context != null && !context.isBlank()) {
                systemPrompt += "\n\n" + context;
            }
            if (profile.planningLengthPolicy()) {
                systemPrompt = applyLengthPolicy(systemPrompt, userMessage);
            }

            /* 3. 消息管线：system + 历史（历史最后一条即本轮 user 消息） */
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
            for (Map<String, String> h : history) {
                messages.add(new LinkedHashMap<>(h));
            }

            /* 4. 有界循环：轮数上限 / 工具总次数上限 / 墙钟死线 三重预算 */
            long deadline = System.currentTimeMillis() + props.getRunTimeoutSeconds() * 1000L;
            int toolCallsUsed = 0;
            List<String> usedTools = new ArrayList<>();
            String lastAssistantText = "";

            for (int round = 0; round < props.getMaxRounds(); round++) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("Agent 运行触达死线 runId={} sessionId={} profile={}", runId, sessionId, profile.name());
                    break;
                }
                /* 熔断检查：打开状态立即降级（快速失败，不再空等网络超时） */
                if (!breaker.tryAcquire()) {
                    metrics.incRunsDegraded();
                    sink.error("LLM_UNAVAILABLE", "AI 服务暂时不可用（熔断保护中），请稍后再试");
                    if (emitLifecycle) {
                        sink.done();
                    }
                    return RunResult.failure("LLM_UNAVAILABLE", "AI 服务暂时不可用（熔断保护中），请稍后再试",
                            runId, usedTools);
                }
                /* 思考态：每轮 LLM 往返前告知前端（工具回喂后的再思考空窗最明显） */
                sink.thinking();

                LlmCall call = callLlm(messages, sink, profile.allowedTools());
                if (call.failed()) {
                    metrics.incRunsDegraded();
                    sink.error(call.errorCode(), call.errorMessage());
                    if (emitLifecycle) {
                        sink.done();
                    }
                    return RunResult.failure(call.errorCode(), call.errorMessage(), runId, usedTools);
                }
                breaker.onSuccess();

                /* 无工具调用 → 最终回答完成：落记忆 + 收敛 */
                if (call.toolCalls().isEmpty()) {
                    String content = call.content() == null ? "" : call.content();
                    memoryStore.append(sessionId, "assistant", content);
                    if (emitLifecycle) {
                        sink.done();
                    }
                    return RunResult.success(content, runId, usedTools);
                }

                /* 有工具调用：assistant(tool_calls) 入管线（分片已拼成完整形态） */
                lastAssistantText = call.content() == null ? "" : call.content();
                Map<String, Object> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", lastAssistantText.isEmpty() ? null : lastAssistantText);
                assistantMsg.put("tool_calls", call.toolCallsNode());
                messages.add(assistantMsg);

                /* 逐个执行：结构化 tool 事件（running → done/failed + 耗时）→ role=tool 结果回喂 */
                for (ToolCall tc : call.toolCalls()) {
                    if (toolCallsUsed >= props.getMaxToolCalls()) {
                        break;
                    }
                    toolCallsUsed++;
                    usedTools.add(tc.name());
                    sink.tool(tc.name(), describeTool(tc.name()), "running", null);
                    long ts = System.currentTimeMillis();
                    metrics.incToolCalls();
                    String result = toolRegistry.invoke(tc.name(), tc.arguments(), user, profile.allowedTools());
                    String status = "done";
                    try {
                        JsonNode rn = objectMapper.readTree(result);
                        if (rn.isObject() && rn.has("error")) {
                            status = "failed";
                            metrics.incToolFailures();
                        }
                    } catch (Exception ignore) {
                        // 非对象结果按成功处理
                    }
                    sink.tool(tc.name(), describeTool(tc.name()), status, System.currentTimeMillis() - ts);

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.id());
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
                if (toolCallsUsed >= props.getMaxToolCalls()) {
                    break;
                }
            }

            /* 5. 预算/死线耗尽：礼貌收尾并把最终可见文案落记忆 */
            String tail = "\n（信息较多，先说到这里，你可以继续追问～）";
            sink.delta(tail);
            String content = lastAssistantText + tail;
            memoryStore.append(sessionId, "assistant", content);
            if (emitLifecycle) {
                sink.done();
            }
            return RunResult.success(content, runId, usedTools);
        } catch (Exception e) {
            log.error("Agent 运行异常 runId={}: {}", runId, e.getMessage(), e);
            metrics.incRunsDegraded();
            sink.error("INTERNAL_ERROR", "AI 助手开小差了，请稍后再试");
            if (emitLifecycle) {
                sink.done();
            }
            return RunResult.failure("INTERNAL_ERROR", "AI 助手开小差了，请稍后再试", runId, List.of());
        } finally {
            metrics.recordRun(System.currentTimeMillis() - t0);
        }
    }

    /**
     * LLM 调用（含硬性上限重试）：仅「正文尚未外发过」且错误可重试（网络/5xx/429）时重试，
     * 避免流式重试造成正文重复输出。
     */
    private LlmCall callLlm(List<Map<String, Object>> messages, AgentSink sink, Set<String> allowedTools) {
        int attempts = 1 + Math.max(0, props.getLlmRetryTimes());
        LlmCall last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                metrics.incLlmRetries();
                try {
                    Thread.sleep(props.getLlmRetryBackoffMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LlmCall call = callLlmOnce(messages, sink, allowedTools);
            if (!call.failed()) {
                return call;
            }
            breaker.onFailure();
            last = call;
            if (!call.retryable()) {
                break;
            }
        }
        return last;
    }

    /** 单次流式 LLM 调用：正文增量透传 + tool_calls 分片按 index 拼接 */
    private LlmCall callLlmOnce(List<Map<String, Object>> messages, AgentSink sink, Set<String> allowedTools) {
        metrics.incLlmCalls();
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", aiProperties.getModel());
            req.put("stream", true);
            req.put("temperature", props.getTemperature());
            ArrayNode msgNode = req.putArray("messages");
            for (Map<String, Object> m : messages) {
                ObjectNode node = msgNode.addObject();
                node.put("role", String.valueOf(m.get("role")));
                Object content = m.get("content");
                if (content == null) {
                    node.putNull("content");
                } else {
                    node.put("content", String.valueOf(content));
                }
                /* assistant 消息可能携带 tool_calls（上一轮工具调用请求） */
                if (m.get("tool_calls") instanceof ArrayNode tcs) {
                    node.set("tool_calls", tcs.deepCopy());
                }
                if (m.get("tool_call_id") != null) {
                    node.put("tool_call_id", String.valueOf(m.get("tool_call_id")));
                }
            }
            req.set("tools", toolRegistry.toolsSchema(allowedTools));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiProperties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("DeepSeek 流式接口异常 status={} body={}", response.statusCode(),
                        errBody.substring(0, Math.min(errBody.length(), 300)));
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                return failed(response.statusCode() == 429 || response.statusCode() >= 500 ? "LLM_UNAVAILABLE" : "LLM_ERROR",
                        "AI 服务繁忙，请稍后再试", retryable);
            }

            /* 流式读取：正文增量透传（可重试性随首个 delta 外发而关闭）+ tool_calls 分片拼接 */
            StringBuilder fullContent = new StringBuilder();
            Map<Integer, String> toolIds = new TreeMap<>();
            Map<Integer, String> toolNames = new TreeMap<>();
            Map<Integer, StringBuilder> toolArgs = new TreeMap<>();
            boolean deltasEmitted = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode node = objectMapper.readTree(payload);
                    JsonNode choice = node.path("choices").path(0);
                    JsonNode delta = choice.path("delta");
                    JsonNode dContent = delta.path("content");
                    /* NullNode 防御：部分分片带 "content": null，asText() 会返回字面量 "null" */
                    if (!dContent.isMissingNode() && !dContent.isNull() && !dContent.asText().isEmpty()) {
                        fullContent.append(dContent.asText());
                        sink.delta(dContent.asText());
                        deltasEmitted = true;
                    }
                    JsonNode tcs = delta.path("tool_calls");
                    if (tcs.isArray()) {
                        for (JsonNode tc : tcs) {
                            int idx = tc.path("index").asInt(0);
                            JsonNode id = tc.path("id");
                            if (!id.isMissingNode() && !id.isNull() && !id.asText().isEmpty()) {
                                toolIds.put(idx, id.asText());
                            }
                            JsonNode fn = tc.path("function");
                            JsonNode fname = fn.path("name");
                            if (!fname.isMissingNode() && !fname.isNull() && !fname.asText().isEmpty()) {
                                toolNames.put(idx, fname.asText());
                            }
                            JsonNode fargs = fn.path("arguments");
                            if (!fargs.isMissingNode() && !fargs.isNull() && !fargs.asText().isEmpty()) {
                                toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(fargs.asText());
                            }
                        }
                    }
                }
            }

            /* 组装完整 tool_calls（id 缺省兜底 call_{index}，arguments 缺省 {}） */
            ArrayNode tcsOut = objectMapper.createArrayNode();
            List<ToolCall> calls = new ArrayList<>();
            for (Map.Entry<Integer, String> en : toolNames.entrySet()) {
                int idx = en.getKey();
                String id = toolIds.getOrDefault(idx, "call_" + idx);
                String args = toolArgs.containsKey(idx) ? toolArgs.get(idx).toString() : "{}";
                ObjectNode tc = tcsOut.addObject();
                tc.put("id", id);
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", en.getValue());
                fn.put("arguments", args);
                calls.add(new ToolCall(id, en.getValue(), args));
            }
            String content = fullContent.toString();
            return new LlmCall(false, null, null, false, content, tcsOut, calls);
        } catch (Exception e) {
            log.error("DeepSeek 流式调用失败: {}", e.getMessage(), e);
            /* 网络中断发生在流中途：无法安全重试（正文可能已外发），只能整体失败 */
            return failed("LLM_UNAVAILABLE", "AI 服务繁忙，请稍后再试", true);
        }
    }

    private LlmCall failed(String code, String message, boolean retryable) {
        return new LlmCall(true, code, message, retryable, null, null, List.of());
    }

    /** 工具执行进度文案（工具时间线 label） */
    private String describeTool(String toolName) {
        return switch (toolName) {
            case "get_my_trips" -> "正在查询你的旅行计划…";
            case "get_trip_detail" -> "正在读取计划日程…";
            case "search_attractions" -> "正在搜索平台景点…";
            case "add_to_trip" -> "正在把景点加入行程…";
            case "optimize_day_order" -> "正在优化行程顺序…";
            case "create_trip" -> "正在创建旅行计划…";
            case "search_notes" -> "正在检索平台游记…";
            case "get_my_orders" -> "正在查询你的订单…";
            case "get_order_detail" -> "正在读取订单详情…";
            case "get_seckill_deals" -> "正在查询秒杀场次…";
            case "get_my_account" -> "正在查询积分与签到…";
            case "search_support_kb" -> "正在检索客服知识库…";
            default -> "正在处理…";
        };
    }
}
