package com.travelplus.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelplus.agent.core.ChatMemoryStore;
import com.travelplus.agent.core.SseSink;
import com.travelplus.agent.guard.AgentRateLimiter;
import com.travelplus.agent.support.SupportAgentOrchestrator;
import com.travelplus.agent.support.SupportProperties;
import com.travelplus.agent.support.SupportSessionStore;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.mapper.ChatMessageMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.ISupportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【企业级客服Agent的实现】AI 智能客服服务：薄服务层，只负责受理、限流、落库与异步调度。
 * <p>
 * 实现细节：
 * - 全部智能逻辑下沉到 SupportAgentOrchestrator（护栏/意图/状态/知识库/工具/转人工/审计），
 *   本类不做任何 Prompt 拼接或 LLM 调用，避免业务逻辑再次堆回 Service；
 * - 请求线程只做：参数校验 → 客服域限流 → 用户消息落库 → 读取会话模式 → 异步提交；
 * - 人工模式（WAITING/HUMAN）下仍异步提交给编排器，但编排器只记录用户消息、不生成 AI 回复，
 *   由人工工作台接管；前端通过返回的 mode 决定展示"思考中"还是"等待人工"；
 * - 保持旧契约：AI/人工回复都写 tb_chat_message（客服虚拟用户 1021），前端轮询 /chat/list 即可；
 * - 失败兜底：Agent 失败写 FALLBACK_REPLY，保证轮询必收敛。
 *
 * @author TravelPlus
 */
@Slf4j
@Service
public class SupportServiceImpl implements ISupportService {

    /** 客服虚拟用户 id（种子数据 id=1021） */
    private static final Long SUPPORT_USER_ID = 1021L;

    /** Agent 失败/空回复时的兜底文案 */
    private static final String FALLBACK_REPLY = "（客服暂时无法回复，请稍后再试）";

    private final ChatMessageMapper chatMessageMapper;
    private final UserMapper userMapper;
    private final ThreadPoolTaskExecutor supportExecutor;
    private final SupportAgentOrchestrator agentOrchestrator;
    private final SupportSessionStore sessionStore;
    private final SupportProperties supportProps;
    private final AgentRateLimiter rateLimiter;
    private final ChatMemoryStore memoryStore;
    private final ObjectMapper objectMapper;

    public SupportServiceImpl(ChatMessageMapper chatMessageMapper,
                              UserMapper userMapper,
                              @Qualifier("supportTaskExecutor") ThreadPoolTaskExecutor supportExecutor,
                              SupportAgentOrchestrator agentOrchestrator,
                              SupportSessionStore sessionStore,
                              SupportProperties supportProps,
                              AgentRateLimiter rateLimiter,
                              ChatMemoryStore memoryStore,
                              ObjectMapper objectMapper) {
        this.chatMessageMapper = chatMessageMapper;
        this.userMapper = userMapper;
        this.supportExecutor = supportExecutor;
        this.agentOrchestrator = agentOrchestrator;
        this.sessionStore = sessionStore;
        this.supportProps = supportProps;
        this.rateLimiter = rateLimiter;
        this.memoryStore = memoryStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 单轮咨询（异步）：请求线程立即返回会话模式，Agent/人工链路在后台推进。
     *
     * @param message 用户消息
     * @param user    当前登录用户
     */
    @Override
    public Result chat(String message, UserDTO user) {
        if (message == null || message.isBlank()) {
            return Result.fail("消息内容不能为空");
        }
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录后再咨询客服");
        }
        Long userId = user.getId();

        /* 客服域独立限流：与旅游助手额度互不影响 */
        AgentRateLimiter.Decision decision = rateLimiter.check(
                "support:u:" + userId, supportProps.getRateLimitPerMinute());
        if (!decision.allowed()) {
            return Result.fail("咨询太频繁了，请 " + decision.retryAfterSeconds() + " 秒后再试");
        }

        /* 用户消息同步落库：前端轮询立即可见，人工工作台也能看到 */
        persistUserMessage(userId, message);

        /* 读取当前模式：AI 模式走 Agent；WAITING/HUMAN 只记录消息，等待人工 */
        SupportSessionStore.SessionState state = sessionStore.load(userId);
        supportExecutor.execute(() -> generateAndPersistReply(user, message));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", state.mode());
        out.put("ticketId", state.ticketId());
        out.put("message", SupportSessionStore.MODE_AI.equals(state.mode())
                ? "已收到你的问题，思考中..." : "已转人工，请等待人工客服回复");
        return Result.ok(out);
    }

    /**
     * 【企业级客服的实现】流式咨询：与旅行 AI 助手一致的 SSE 协议。
     * <p>
     * 生命周期：meta → delta/tool/replace →（落库拿到 messageId）→ done。
     * 请求线程完成校验/限流/用户消息落库后立即返回 emitter，Agent 在客服线程池异步推送。
     *
     * @param message 用户消息
     * @param user    当前登录用户
     * @return SSE emitter
     */
    @Override
    public SseEmitter chatStream(String message, UserDTO user) {
        SseEmitter emitter = new SseEmitter(120_000L);
        SseSink sink = new SseSink(emitter, objectMapper);

        if (message == null || message.isBlank()) {
            sink.error("INVALID_INPUT", "消息内容不能为空");
            sink.done();
            return emitter;
        }
        if (user == null || user.getId() == null) {
            sink.error("UNAUTHENTICATED", "请先登录后再咨询客服");
            sink.done();
            return emitter;
        }
        Long userId = user.getId();
        AgentRateLimiter.Decision decision = rateLimiter.check(
                "support:u:" + userId, supportProps.getRateLimitPerMinute());
        if (!decision.allowed()) {
            sink.error("RATE_LIMITED", "咨询太频繁了，请 " + decision.retryAfterSeconds() + " 秒后再试");
            sink.done();
            return emitter;
        }

        persistUserMessage(userId, message);

        supportExecutor.execute(() -> {
            Long messageId = null;
            try {
                SupportAgentOrchestrator.Result result = agentOrchestrator.stream(user, message, sink);
                if (result.reply() != null && !result.reply().isBlank()) {
                    messageId = persistAiReply(userId, result.reply());
                }
            } catch (Exception e) {
                log.error("【客服】流式 Agent 运行失败 userId={} message={}", userId, message, e);
                messageId = persistAiReply(userId, FALLBACK_REPLY);
                sink.replace(FALLBACK_REPLY);
            } finally {
                /* done 必须最后发：前端拿到 messageId 才能挂载反馈按钮，并解锁输入框 */
                sink.done(messageId);
            }
        });
        return emitter;
    }

    /** 清空当前用户的客服 Agent 记忆（界面「清空对话」；不影响已提交的人工工单） */
    @Override
    public Result clear(Long userId) {
        if (userId == null) {
            return Result.fail("请先登录");
        }
        memoryStore.clear(SupportSessionStore.memorySessionId(userId));
        return Result.ok();
    }

    /**
     * 异步任务体：调用客服 Agent 状态机，按结果落库或静默（人工模式）。
     * 线程池任务必须自行收敛所有异常。
     */
    private void generateAndPersistReply(UserDTO user, String message) {
        Long userId = user == null ? null : user.getId();
        try {
            SupportAgentOrchestrator.Result result = agentOrchestrator.handle(user, message);
            if (result.reply() != null && !result.reply().isBlank()) {
                persistAiReply(userId, result.reply());
                return;
            }
            if (!SupportSessionStore.MODE_AI.equals(result.mode())) {
                /* 人工模式：不生成 AI 回复，消息已由编排器写入上下文，等待工作台回复 */
                log.info("【客服】人工模式跳过 AI 回复 userId={} mode={} ticketId={}",
                        userId, result.mode(), result.ticketId());
                return;
            }
            log.warn("【客服】Agent 返回空回复 userId={} intent={}", userId, result.intent());
            persistAiReply(userId, FALLBACK_REPLY);
        } catch (Exception e) {
            log.error("【客服】Agent 运行失败 userId={} message={}", userId, message, e);
            persistAiReply(userId, FALLBACK_REPLY);
        }
    }

    /**
     * 同步落库用户消息（from=当前用户→客服，is_read=1）。
     * 未登录、用户/客服不存在、写库异常均只记日志，不影响接口返回。
     */
    private void persistUserMessage(Long userId, String message) {
        try {
            if (userMapper.selectById(userId) == null) {
                log.warn("客服对话落库跳过：当前用户不存在 userId={}", userId);
                return;
            }
            if (userMapper.selectById(SUPPORT_USER_ID) == null) {
                log.warn("客服对话落库跳过：客服虚拟用户缺失 supportUserId={}", SUPPORT_USER_ID);
                return;
            }
            com.travelplus.entity.ChatMessage userMsg = new com.travelplus.entity.ChatMessage();
            userMsg.setFromUserId(userId);
            userMsg.setToUserId(SUPPORT_USER_ID);
            userMsg.setContent(message);
            userMsg.setIsRead(1);
            chatMessageMapper.insert(userMsg);
        } catch (Exception e) {
            log.warn("客服用户消息落库失败: userId={}", userId, e);
        }
    }

    /**
     * 异步落库客服回复（from=客服→用户，is_read=0，计入未读角标）。
     * 写库失败只记日志，不在异步线程中抛异常。
     */
    private Long persistAiReply(Long userId, String reply) {
        if (userId == null) {
            return null;
        }
        try {
            if (userMapper.selectById(userId) == null) {
                log.warn("客服对话落库跳过：当前用户不存在 userId={}", userId);
                return null;
            }
            if (userMapper.selectById(SUPPORT_USER_ID) == null) {
                log.warn("客服对话落库跳过：客服虚拟用户缺失 supportUserId={}", SUPPORT_USER_ID);
                return null;
            }
            com.travelplus.entity.ChatMessage supportMsg = new com.travelplus.entity.ChatMessage();
            supportMsg.setFromUserId(SUPPORT_USER_ID);
            supportMsg.setToUserId(userId);
            supportMsg.setContent(reply);
            supportMsg.setIsRead(0);
            chatMessageMapper.insert(supportMsg);
            return supportMsg.getId();
        } catch (Exception e) {
            log.warn("客服 AI 回复落库失败: userId={}", userId, e);
            return null;
        }
    }
}
