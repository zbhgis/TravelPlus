package com.travelplus.agent.support;

import com.travelplus.agent.core.AgentOrchestrator;
import com.travelplus.agent.core.AgentProfile;
import com.travelplus.agent.core.AgentSink;
import com.travelplus.agent.core.ChatMemoryStore;
import com.travelplus.agent.core.PromptRegistry;
import com.travelplus.agent.core.SilentSink;
import com.travelplus.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 【企业级客服Agent的实现】客服 Agent 状态机编排器（对标 LangGraph 的显式图执行）。
 * <p>
 * 执行图：
 * <pre>
 * clean → input_guard ─┬─ blocked → 安全话术
 *                      └─ pass → load_state → intent_route
 *                                   ├─ 非 AI 模式（WAITING/HUMAN）→ 记录消息，等待人工
 *                                   ├─ HUMAN → 创建工单 → 回执（AI 静默）
 *                                   ├─ COMPLAINT → 创建工单 + 安抚回执（AI 静默）
 *                                   └─ 其他意图 → 知识库接地 + 提示词策略 + 工具白名单
 *                                                  → Agent 运行（有界工具循环）→ 输出护栏
 *                                                  → 会话状态/指标/审计 → 回复
 * </pre>
 * 实现细节：
 * - 状态机而非"一次 LLM 调用"：每个阶段职责单一、可审计、可单测；
 * - 人工接管是一等状态：WAITING/HUMAN 时 AI 完全静默，避免"机器人和人工同时回复"；
 * - 投诉/账号安全走确定性转人工，不交给模型自由发挥，降低企业风险；
 * - 工具白名单随意图下发，模型看不到也调不动行程写操作等无关能力；
 * - 输出护栏兜底空回复、内部信息泄露与手机号脱敏。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class SupportAgentOrchestrator {

    private final SupportIntentRouter intentRouter;
    private final SupportGuardrails guardrails;
    private final SupportSessionStore sessionStore;
    private final SupportHandoffService handoffService;
    private final SupportKnowledgeBase knowledgeBase;
    private final SupportPromptPolicy promptPolicy;
    private final SupportMetrics metrics;
    private final ChatMemoryStore memoryStore;
    private final AgentOrchestrator agentOrchestrator;

    public SupportAgentOrchestrator(SupportIntentRouter intentRouter,
                                    SupportGuardrails guardrails,
                                    SupportSessionStore sessionStore,
                                    SupportHandoffService handoffService,
                                    SupportKnowledgeBase knowledgeBase,
                                    SupportPromptPolicy promptPolicy,
                                    SupportMetrics metrics,
                                    ChatMemoryStore memoryStore,
                                    AgentOrchestrator agentOrchestrator) {
        this.intentRouter = intentRouter;
        this.guardrails = guardrails;
        this.sessionStore = sessionStore;
        this.handoffService = handoffService;
        this.knowledgeBase = knowledgeBase;
        this.promptPolicy = promptPolicy;
        this.metrics = metrics;
        this.memoryStore = memoryStore;
        this.agentOrchestrator = agentOrchestrator;
    }

    /**
     * 处理一条客服咨询（非流式，兼容旧轮询接口）。
     *
     * @param user       当前登录用户
     * @param rawMessage 原始消息
     * @return 回复 + 模式 + 意图 + 审计轨迹；reply 为空表示人工模式下无需 AI 回复
     */
    public Result handle(UserDTO user, String rawMessage) {
        return execute(user, rawMessage, new SilentSink(), false);
    }

    /**
     * 【企业级客服Agent的实现】流式处理：与旅行 AI 助手一致的 SSE 事件协议。
     * <p>
     * 生命周期：本方法先发 meta，再执行状态机；delta/tool/replace 事件实时外发；
     * done 由 SupportServiceImpl 在回复落库拿到消息 id 后发送，保证前端拿到反馈所需的 messageId。
     *
     * @param user       当前登录用户
     * @param rawMessage 原始消息
     * @param sink       SSE 事件写出器
     * @return 与 handle 相同的结果（供落库/审计）
     */
    public Result stream(UserDTO user, String rawMessage, AgentSink sink) {
        Long userId = user == null ? null : user.getId();
        String runId = "spt_" + UUID.randomUUID().toString().substring(0, 8);
        sink.meta(runId, SupportSessionStore.memorySessionId(userId));
        return execute(user, rawMessage, sink, true);
    }

    /**
     * 客服状态机主流程：流式/非流式共用，streaming=true 时把阶段性回复以 delta 外发。
     */
    private Result execute(UserDTO user, String rawMessage, AgentSink sink, boolean streaming) {
        long t0 = System.currentTimeMillis();
        List<String> trace = new ArrayList<>();
        Long userId = user == null ? null : user.getId();
        String message = guardrails.clean(rawMessage);
        trace.add("clean");

        /* 1. 输入护栏：注入/凭证套取直接拦截，不调用 LLM */
        SupportGuardrails.Decision input = guardrails.checkInput(message);
        if (input.blocked()) {
            metrics.incGuardBlocks();
            long elapsed = System.currentTimeMillis() - t0;
            metrics.recordRun(SupportIntent.OTHER, elapsed);
            log.info("【SupportAgent】input_guard_block userId={} reason={} elapsedMs={}",
                    userId, input.reason(), elapsed);
            if (streaming) {
                sink.delta(input.reply());
            }
            return new Result(input.reply(), SupportSessionStore.MODE_AI, SupportIntent.OTHER,
                    true, null, "", List.of(), elapsed, trace);
        }

        /* 2. 加载会话状态 + 意图路由（短追问继承上一轮意图） */
        SupportSessionStore.SessionState state = sessionStore.load(userId);
        SupportIntentRouter.Route route = intentRouter.route(message, parseIntent(state.intent()));
        trace.add("intent=" + route.intent().name());

        /* 3. 人工模式：AI 静默；用户可主动恢复 AI */
        if (!SupportSessionStore.MODE_AI.equals(state.mode())) {
            if (isResumeCommand(message)) {
                handoffService.resumeByUser(userId);
                String reply = "已结束人工服务，AI 客服继续为你服务～";
                appendMemory(userId, message, reply);
                bumpSession(userId, route.intent(), "");
                long elapsed = System.currentTimeMillis() - t0;
                metrics.recordRun(route.intent(), elapsed);
                log.info("【SupportAgent】resume_ai userId={} elapsedMs={}", userId, elapsed);
                if (streaming) {
                    sink.delta(reply);
                }
                return new Result(reply, SupportSessionStore.MODE_AI, route.intent(), false,
                        null, "", List.of(), elapsed, trace);
            }
            /* 人工服务中：消息落记忆，人工通过工作台回复；本轮不生成 AI 回复 */
            memoryStore.append(SupportSessionStore.memorySessionId(userId), "user", message);
            long elapsed = System.currentTimeMillis() - t0;
            metrics.recordRun(route.intent(), elapsed);
            log.info("【SupportAgent】human_mode_skip userId={} mode={} ticketId={} elapsedMs={}",
                    userId, state.mode(), state.ticketId(), elapsed);
            if (streaming) {
                sink.delta("已转人工，请等待人工客服回复～");
            }
            return new Result(null, state.mode(), route.intent(), false, state.ticketId(),
                    "", List.of(), elapsed, trace);
        }

        /* 4. 明确要求人工：确定性转接，不调用 LLM */
        if (route.intent() == SupportIntent.HUMAN) {
            return directHandoff(userId, message, route, true, t0, trace, sink, streaming);
        }

        /* 5. 投诉/账号安全/资金争议：先安抚并转人工，避免模型过度承诺 */
        if (route.intent() == SupportIntent.COMPLAINT) {
            return directHandoff(userId, message, route, false, t0, trace, sink, streaming);
        }

        /* 6. 知识库接地 + 意图策略 + 工具白名单 → Agent 运行 */
        Set<String> allowedTools = allowedTools(route.intent());
        String kbContext = knowledgeBase.buildContext(message);
        String policy = promptPolicy.instructions(route.intent());
        AgentProfile profile = new AgentProfile(
                "support-" + route.intent().name().toLowerCase(),
                PromptRegistry.SUPPORT_AGENT,
                false,
                q -> (kbContext.isBlank() ? "" : kbContext + "\n\n") + policy,
                allowedTools);

        String sessionId = SupportSessionStore.memorySessionId(userId);
        AgentOrchestrator.RunResult run = streaming
                ? agentOrchestrator.streamWithProfile(sessionId, message, user, sink, profile)
                : agentOrchestrator.runToText(sessionId, message, user, profile);
        trace.add("agent_run");

        /* 7. 输出护栏 + 会话状态（保留工具转人工期间写入的 WAITING 状态） */
        boolean guardBlocked = false;
        String reply;
        if (!run.ok()) {
            metrics.incFallbacks();
            reply = "抱歉，客服系统暂时繁忙，请稍后再试。你也可以回复「转人工」联系人工客服。";
            if (streaming) {
                /* 流式可能已有半截内容或为空，用兜底文案整体替换，避免残句 */
                sink.replace(reply);
            }
        } else {
            String raw = run.content() == null ? "" : run.content();
            SupportGuardrails.Decision output = guardrails.checkOutput(raw);
            guardBlocked = output.blocked();
            if (guardBlocked) {
                metrics.incGuardBlocks();
            }
            reply = output.reply();
            /* 流式已外发原文：护栏若修正（脱敏/泄露拦截），发 replace 覆盖前端正文 */
            if (streaming && !reply.equals(raw)) {
                sink.replace(reply);
            }
        }
        bumpSession(userId, route.intent(), run.runId());
        SupportSessionStore.SessionState latest = sessionStore.load(userId);
        long elapsed = System.currentTimeMillis() - t0;
        metrics.recordRun(route.intent(), elapsed);

        log.info("【SupportAgent】runId={} userId={} intent={} mode={} guardBlocked={} tools={} elapsedMs={} trace={}",
                run.runId(), userId, route.intent().name(), latest.mode(), guardBlocked,
                run.toolsUsed(), elapsed, trace);
        return new Result(reply, latest.mode(), route.intent(), guardBlocked, latest.ticketId(),
                run.runId(), run.toolsUsed(), elapsed, trace);
    }

    /** 确定性转人工：创建工单 + 回执，AI 静默 */
    private Result directHandoff(Long userId, String message, SupportIntentRouter.Route route,
                                 boolean explicit, long t0, List<String> trace,
                                 AgentSink sink, boolean streaming) {
        SupportHandoffService handoff = handoffService;
        com.travelplus.entity.SupportHandoff ticket =
                handoff.request(userId, message, explicit ? "user" : "agent");
        int waiting = handoff.waitingCount();
        String reply = explicit
                ? "已为你转接人工客服（工单号 " + ticket.getId() + "）。当前待接入 " + waiting
                    + " 人，请稍候，人工客服会在这里回复你。"
                : "非常抱歉给你带来不好的体验。我已经记录你的问题并转接人工客服（工单号 "
                    + ticket.getId() + "），人工客服会尽快核实处理。";
        appendMemory(userId, message, reply);
        bumpSession(userId, route.intent(), "");
        SupportSessionStore.SessionState latest = sessionStore.load(userId);
        long elapsed = System.currentTimeMillis() - t0;
        metrics.recordRun(route.intent(), elapsed);
        trace.add("handoff");
        log.info("【SupportAgent】handoff userId={} ticketId={} intent={} source={} elapsedMs={}",
                userId, ticket.getId(), route.intent().name(), ticket.getSource(), elapsed);
        if (streaming) {
            sink.delta(reply);
        }
        return new Result(reply, latest.mode(), route.intent(), false, latest.ticketId(),
                "", List.of(), elapsed, trace);
    }

    /** 更新会话轮次/意图，始终保留当前 mode 与 ticketId（工具可能已转人工） */
    private void bumpSession(Long userId, SupportIntent intent, String runId) {
        SupportSessionStore.SessionState s = sessionStore.load(userId);
        sessionStore.save(userId, new SupportSessionStore.SessionState(
                s.mode(), s.ticketId(), intent.name(), s.turnCount() + 1, runId,
                LocalDateTime.now().toString()));
    }

    private void appendMemory(Long userId, String userMessage, String assistantReply) {
        String sid = SupportSessionStore.memorySessionId(userId);
        memoryStore.append(sid, "user", userMessage);
        memoryStore.append(sid, "assistant", assistantReply);
    }

    private boolean isResumeCommand(String message) {
        return message.contains("恢复AI") || message.contains("恢复ai")
                || message.contains("结束人工") || message.contains("退出人工")
                || message.contains("不用人工") || message.contains("结束转人工");
    }

    /** 会话中持久化的意图字符串 → 枚举（非法/空返回 null） */
    private SupportIntent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SupportIntent.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 意图 → 工具白名单（最小权限）：
     * 客服永远看不到行程写操作；订单类才给订单工具，账户类才给资产工具。
     */
    private Set<String> allowedTools(SupportIntent intent) {
        Set<String> tools = new LinkedHashSet<>(List.of(
                "search_support_kb", "search_attractions", "search_notes", "request_human_handoff"));
        switch (intent) {
            case ORDER -> tools.addAll(List.of("get_my_orders", "get_order_detail"));
            case ACCOUNT -> tools.add("get_my_account");
            case SECKILL -> tools.add("get_seckill_deals");
            case COMPLAINT -> tools.addAll(List.of("get_my_orders", "get_order_detail"));
            default -> {
                // FAQ/OTHER/HUMAN：仅知识库与转人工
            }
        }
        return tools;
    }

    /** 客服 Agent 单轮结果：reply=最终回复（人工等待时为空）；mode=AI/WAITING/HUMAN */
    public record Result(String reply, String mode, SupportIntent intent, boolean guardBlocked,
                         Long ticketId, String runId, List<String> toolsUsed, long elapsedMs,
                         List<String> trace) {
    }
}
