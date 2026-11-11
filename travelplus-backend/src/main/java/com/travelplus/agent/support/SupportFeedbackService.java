package com.travelplus.agent.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelplus.dto.Result;
import com.travelplus.entity.ChatMessage;
import com.travelplus.entity.SupportFeedback;
import com.travelplus.entity.User;
import com.travelplus.mapper.ChatMessageMapper;
import com.travelplus.mapper.SupportFeedbackMapper;
import com.travelplus.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 【企业级客服Agent的实现】客服回复反馈服务。
 * <p>
 * 实现细节：
 * - 用户对单条客服回复点赞/点踩，写入 tb_support_feedback，供在线质量评估与 bad case 回收；
 * - uk_user_message(user_id, message_id) 保证同一条回复只计一次反馈，重复提交幂等返回；
 * - 反馈量计入 SupportMetrics，工作台可查看正负反馈总数。
 *
 * @author TravelPlus
 */
@Slf4j
@Service
public class SupportFeedbackService {

    @Resource
    private SupportFeedbackMapper feedbackMapper;

    @Resource
    private SupportProperties props;

    @Resource
    private SupportMetrics metrics;

    @Resource
    private UserMapper userMapper;

    @Resource
    private ChatMessageMapper chatMessageMapper;

    /**
     * 【客服Agent完善】差评/bad case 回收：按 helpful 过滤反馈，关联用户昵称与原始回复内容，
     * 供工作台直接查看「用户为什么觉得没帮助」，形成「反馈 → 归因 → 优化知识库/提示词」闭环。
     *
     * @param helpful true=好评 false=差评 null=全部
     * @param limit   返回条数（1~200，默认 50）
     */
    public List<Map<String, Object>> list(Boolean helpful, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<SupportFeedback> qw = new LambdaQueryWrapper<>();
        if (helpful != null) {
            qw.eq(SupportFeedback::getHelpful, helpful);
        }
        qw.orderByDesc(SupportFeedback::getCreateTime).last("LIMIT " + size);
        List<SupportFeedback> rows = feedbackMapper.selectList(qw);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = new LinkedHashMap<>();
        Set<Long> userIds = rows.stream().map(SupportFeedback::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!userIds.isEmpty()) {
            for (User u : userMapper.selectBatchIds(userIds)) {
                users.put(u.getId(), u);
            }
        }
        Map<Long, ChatMessage> messages = new LinkedHashMap<>();
        Set<Long> messageIds = rows.stream().map(SupportFeedback::getMessageId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!messageIds.isEmpty()) {
            for (ChatMessage m : chatMessageMapper.selectBatchIds(messageIds)) {
                messages.put(m.getId(), m);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportFeedback f : rows) {
            User u = users.get(f.getUserId());
            ChatMessage m = f.getMessageId() == null ? null : messages.get(f.getMessageId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.getId());
            item.put("userId", f.getUserId());
            item.put("nickName", u == null ? "用户" + f.getUserId() : u.getNickName());
            item.put("messageId", f.getMessageId());
            item.put("intent", f.getIntent());
            item.put("helpful", f.getHelpful());
            item.put("reason", f.getReason());
            item.put("createTime", f.getCreateTime());
            item.put("messageContent", m == null ? null : truncate(m.getContent(), 300));
            item.put("messageTime", m == null ? null : m.getCreateTime());
            out.add(item);
        }
        return out;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * 提交反馈。
     *
     * @param userId    用户 id
     * @param messageId 被评价的客服消息 id（可为空）
     * @param helpful   1=有帮助 0=没帮助
     * @param reason    点踩原因（可选）
     * @param intent    当时意图（可选）
     */
    public Result submit(Long userId, Long messageId, Boolean helpful, String reason, String intent) {
        if (!props.isFeedbackEnabled()) {
            return Result.fail("反馈功能暂未开启");
        }
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (helpful == null) {
            return Result.fail("请选择是否有帮助");
        }
        SupportFeedback feedback = new SupportFeedback();
        feedback.setUserId(userId);
        feedback.setMessageId(messageId);
        feedback.setHelpful(helpful);
        feedback.setReason(reason == null ? null : reason.trim().substring(0, Math.min(reason.trim().length(), 500)));
        feedback.setIntent(intent == null ? null : intent.trim().substring(0, Math.min(intent.trim().length(), 32)));
        feedback.setCreateTime(LocalDateTime.now());
        try {
            feedbackMapper.insert(feedback);
            metrics.incFeedback(helpful);
            return Result.ok();
        } catch (DuplicateKeyException e) {
            /* 同一消息重复反馈：幂等成功，不重复计数 */
            return Result.ok();
        } catch (Exception e) {
            log.warn("客服反馈落库失败 userId={} messageId={}: {}", userId, messageId, e.getMessage());
            return Result.fail("反馈提交失败，请稍后再试");
        }
    }
}
