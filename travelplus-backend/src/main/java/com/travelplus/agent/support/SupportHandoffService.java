package com.travelplus.agent.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelplus.agent.core.ChatMemoryStore;
import com.travelplus.dto.Result;
import com.travelplus.entity.SupportHandoff;
import com.travelplus.entity.User;
import com.travelplus.mapper.ChatMessageMapper;
import com.travelplus.mapper.SupportHandoffMapper;
import com.travelplus.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【企业级客服Agent的实现】人工转接服务（Human-in-the-loop）。
 * <p>
 * 实现细节：
 * - 工单状态机：0=待接入（AI 静默）→ 1=人工服务中 → 2=已结束（恢复 AI）；
 * - 幂等：同一用户已有未结束工单时直接复用，避免重复排队；
 * - 状态联动：每次工单状态变化同步 SupportSessionStore，AI 是否自动回复以状态为准；
 * - 人工回复复用客服虚拟用户 1021 写入 tb_chat_message，用户端轮询即可收到，无需新通道；
 * - 上下文连续：人工回复同时写入 ChatMemoryStore，人工结束恢复 AI 后模型知道刚才的沟通内容；
 * - 并发：同一用户的转人工请求加服务级同步锁（低频操作），避免并发创建重复工单。
 *
 * @author TravelPlus
 */
@Slf4j
@Service
public class SupportHandoffService {

    /** 客服虚拟用户 id（与种子数据一致） */
    public static final Long SUPPORT_USER_ID = 1021L;

    private static final int STATUS_WAITING = 0;
    private static final int STATUS_HUMAN = 1;
    private static final int STATUS_CLOSED = 2;

    private static final int MAX_REASON_LEN = 500;
    private static final int MAX_REPLY_LEN = 2000;

    @Resource
    private SupportHandoffMapper handoffMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private ChatMessageMapper chatMessageMapper;

    @Resource
    private SupportSessionStore sessionStore;

    @Resource
    private ChatMemoryStore memoryStore;

    @Resource
    private SupportMetrics metrics;

    /**
     * 创建/复用人工工单。
     *
     * @param userId 用户 id
     * @param reason 转人工原因
     * @param source 来源 user/agent
     * @return 工单实体
     */
    public synchronized SupportHandoff request(Long userId, String reason, String source) {
        SupportHandoff open = openTicket(userId);
        if (open != null) {
            return open;
        }
        SupportHandoff ticket = new SupportHandoff();
        ticket.setUserId(userId);
        ticket.setReason(truncate(reason, MAX_REASON_LEN));
        ticket.setStatus(STATUS_WAITING);
        ticket.setSource(source == null ? "user" : source);
        ticket.setCreateTime(LocalDateTime.now());
        handoffMapper.insert(ticket);
        sessionStore.markHandoff(userId, ticket.getId(), SupportSessionStore.MODE_WAITING,
                sessionStore.load(userId).intent());
        metrics.incHandoffs();
        log.info("【客服转人工】创建工单 userId={} ticketId={} source={}", userId, ticket.getId(), ticket.getSource());
        return ticket;
    }

    /** 人工客服接入工单（条件更新，防并发重复接入） */
    public SupportHandoff claim(Long adminId, Long ticketId) {
        UpdateWrapper<SupportHandoff> uw = new UpdateWrapper<>();
        uw.eq("id", ticketId).eq("status", STATUS_WAITING)
                .set("status", STATUS_HUMAN)
                .set("assignee_id", adminId)
                .set("claim_time", LocalDateTime.now());
        int rows = handoffMapper.update(null, uw);
        SupportHandoff ticket = handoffMapper.selectById(ticketId);
        if (ticket == null) {
            throw new IllegalStateException("工单不存在");
        }
        if (rows == 0) {
            if (ticket.getStatus() == null || ticket.getStatus() != STATUS_HUMAN) {
                throw new IllegalStateException("工单已被其他人接入或已结束");
            }
            if (ticket.getAssigneeId() != null && !ticket.getAssigneeId().equals(adminId)) {
                throw new IllegalStateException("工单已被其他客服接入");
            }
        }
        sessionStore.markHandoff(ticket.getUserId(), ticketId, SupportSessionStore.MODE_HUMAN,
                sessionStore.load(ticket.getUserId()).intent());
        log.info("【客服转人工】工单接入 ticketId={} adminId={}", ticketId, adminId);
        return ticket;
    }

    /**
     * 人工客服回复用户：写入私信消息表 + Agent 记忆，用户端轮询可见。
     * 未接入的工单会先自动接入。
     */
    public void reply(Long adminId, Long ticketId, String content) {
        String text = truncate(content, MAX_REPLY_LEN);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        SupportHandoff ticket = handoffMapper.selectById(ticketId);
        if (ticket == null) {
            throw new IllegalStateException("工单不存在");
        }
        if (Integer.valueOf(STATUS_CLOSED).equals(ticket.getStatus())) {
            throw new IllegalStateException("工单已结束，无法回复");
        }
        if (Integer.valueOf(STATUS_WAITING).equals(ticket.getStatus())) {
            ticket = claim(adminId, ticketId);
        }
        insertSupportMessage(ticket.getUserId(), text);
        log.info("【客服转人工】人工回复 ticketId={} adminId={} userId={}", ticketId, adminId, ticket.getUserId());
    }

    /** 结束人工会话并恢复 AI；byUser=true 表示用户主动结束 */
    public void close(Long operatorId, Long ticketId, boolean byUser) {
        SupportHandoff ticket = handoffMapper.selectById(ticketId);
        if (ticket == null) {
            throw new IllegalStateException("工单不存在");
        }
        if (Integer.valueOf(STATUS_CLOSED).equals(ticket.getStatus())) {
            return;
        }
        UpdateWrapper<SupportHandoff> uw = new UpdateWrapper<>();
        uw.eq("id", ticketId).in("status", STATUS_WAITING, STATUS_HUMAN)
                .set("status", STATUS_CLOSED)
                .set("close_time", LocalDateTime.now());
        if (!byUser && operatorId != null && ticket.getAssigneeId() == null) {
            uw.set("assignee_id", operatorId);
        }
        handoffMapper.update(null, uw);
        sessionStore.resetToAi(ticket.getUserId(), "");
        String tip = byUser
                ? "已结束人工服务，AI 客服继续为你服务～如果方便，可以点击下方 👍/👎 评价本次服务。"
                : "人工客服已结束本次服务，AI 客服继续为你服务～如果方便，可以点击下方 👍/👎 评价本次服务。";
        insertSupportMessage(ticket.getUserId(), tip);
        log.info("【客服转人工】工单结束 ticketId={} byUser={} operator={}", ticketId, byUser, operatorId);
    }

    /** 用户端发送一条系统提示（转人工成功回执等） */
    public void notifyUser(Long userId, String content) {
        if (userId == null || content == null || content.isBlank()) {
            return;
        }
        insertSupportMessage(userId, truncate(content, MAX_REPLY_LEN));
    }

    /** 当前用户的会话状态 + 工单详情 */
    public Map<String, Object> status(Long userId) {
        SupportSessionStore.SessionState state = sessionStore.load(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", state.mode());
        out.put("ticketId", state.ticketId());
        out.put("intent", state.intent());
        out.put("turnCount", state.turnCount());
        out.put("waitingCount", waitingCount());
        if (state.ticketId() != null) {
            SupportHandoff ticket = handoffMapper.selectById(state.ticketId());
            if (ticket != null) {
                out.put("ticketStatus", ticket.getStatus());
                out.put("ticketStatusText", statusText(ticket.getStatus()));
                out.put("reason", ticket.getReason());
                out.put("createTime", ticket.getCreateTime());
            }
        }
        return out;
    }

    /** 工作台工单列表（status 为空=全部） */
    public List<Map<String, Object>> list(Integer status) {
        LambdaQueryWrapper<SupportHandoff> qw = new LambdaQueryWrapper<>();
        if (status != null) {
            qw.eq(SupportHandoff::getStatus, status);
        }
        qw.orderByAsc(SupportHandoff::getCreateTime).last("LIMIT 100");
        List<SupportHandoff> tickets = handoffMapper.selectList(qw);
        if (tickets.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = tickets.stream().map(SupportHandoff::getUserId).distinct().toList();
        Map<Long, User> users = new LinkedHashMap<>();
        for (User u : userMapper.selectBatchIds(userIds)) {
            users.put(u.getId(), u);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportHandoff t : tickets) {
            User u = users.get(t.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("userId", t.getUserId());
            m.put("nickName", u == null ? "用户" + t.getUserId() : u.getNickName());
            m.put("icon", u == null ? "" : u.getIcon());
            m.put("reason", t.getReason());
            m.put("status", t.getStatus());
            m.put("statusText", statusText(t.getStatus()));
            m.put("source", t.getSource());
            m.put("assigneeId", t.getAssigneeId());
            m.put("createTime", t.getCreateTime());
            m.put("claimTime", t.getClaimTime());
            m.put("closeTime", t.getCloseTime());
            out.add(m);
        }
        return out;
    }

    /** 工作台：查看工单完整会话记录（用户 ↔ 客服 1021） */
    public List<Map<String, Object>> messages(Long ticketId) {
        SupportHandoff ticket = handoffMapper.selectById(ticketId);
        if (ticket == null) {
            throw new IllegalStateException("工单不存在");
        }
        Long userId = ticket.getUserId();
        LambdaQueryWrapper<com.travelplus.entity.ChatMessage> qw = new LambdaQueryWrapper<>();
        qw.and(w -> w.eq(com.travelplus.entity.ChatMessage::getFromUserId, userId)
                        .eq(com.travelplus.entity.ChatMessage::getToUserId, SUPPORT_USER_ID)
                        .or()
                        .eq(com.travelplus.entity.ChatMessage::getFromUserId, SUPPORT_USER_ID)
                        .eq(com.travelplus.entity.ChatMessage::getToUserId, userId))
                .orderByAsc(com.travelplus.entity.ChatMessage::getCreateTime)
                .orderByAsc(com.travelplus.entity.ChatMessage::getId)
                .last("LIMIT 200");
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.travelplus.entity.ChatMessage msg : chatMessageMapper.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", msg.getId());
            m.put("fromUserId", msg.getFromUserId());
            m.put("fromSupport", SUPPORT_USER_ID.equals(msg.getFromUserId()));
            m.put("content", msg.getContent());
            m.put("createTime", msg.getCreateTime());
            out.add(m);
        }
        return out;
    }

    /** 用户主动结束人工会话（只允许结束自己名下未结束的工单） */
    public Result resumeByUser(Long userId) {
        SupportHandoff open = openTicket(userId);
        if (open == null) {
            sessionStore.resetToAi(userId, "");
            return Result.ok();
        }
        close(null, open.getId(), true);
        return Result.ok();
    }

    /** 待接入队列长度 */
    public int waitingCount() {
        Long count = handoffMapper.selectCount(
                new LambdaQueryWrapper<SupportHandoff>().eq(SupportHandoff::getStatus, STATUS_WAITING));
        return count == null ? 0 : count.intValue();
    }

    /** 用户当前未结束工单（无则 null） */
    public SupportHandoff openTicket(Long userId) {
        if (userId == null) {
            return null;
        }
        LambdaQueryWrapper<SupportHandoff> qw = new LambdaQueryWrapper<>();
        qw.eq(SupportHandoff::getUserId, userId)
                .in(SupportHandoff::getStatus, STATUS_WAITING, STATUS_HUMAN)
                .orderByDesc(SupportHandoff::getId)
                .last("LIMIT 1");
        return handoffMapper.selectOne(qw);
    }

    private void insertSupportMessage(Long userId, String content) {
        com.travelplus.entity.ChatMessage msg = new com.travelplus.entity.ChatMessage();
        msg.setFromUserId(SUPPORT_USER_ID);
        msg.setToUserId(userId);
        msg.setContent(content);
        msg.setIsRead(0);
        chatMessageMapper.insert(msg);
        memoryStore.append(SupportSessionStore.memorySessionId(userId), "assistant", content);
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_WAITING -> "待接入";
            case STATUS_HUMAN -> "人工服务中";
            case STATUS_CLOSED -> "已结束";
            default -> "未知";
        };
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
