package com.travelplus.controller;

import com.travelplus.agent.support.SupportFeedbackService;
import com.travelplus.agent.support.SupportHandoffService;
import com.travelplus.agent.support.SupportKnowledgeBase;
import com.travelplus.agent.support.SupportMetrics;
import com.travelplus.agent.support.SupportProperties;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.SupportHandoff;
import com.travelplus.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【企业级客服Agent的实现】客服工作台（人工客服侧）接口。
 * <p>
 * 能力：管理员校验、工单列表、查看完整会话、接入、回复、结束（恢复 AI）、客服指标。
 * 权限：当前演示环境用 travelplus.support.admin-user-ids 白名单（默认 1 号用户），
 * 生产应替换为 RBAC 角色与细粒度审批；所有接口仍受登录拦截器保护。
 *
 * @author TravelPlus
 */
@Slf4j
@RestController
@RequestMapping("/support/admin")
public class SupportAdminController {

    @Resource
    private SupportHandoffService handoffService;

    @Resource
    private SupportMetrics supportMetrics;

    @Resource
    private SupportProperties supportProps;

    @Resource
    private SupportKnowledgeBase knowledgeBase;

    @Resource
    private SupportFeedbackService feedbackService;

    /** 当前用户是否客服工作台管理员（前端据此决定是否展示入口） */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        boolean admin = isAdmin(user);
        return Result.ok(Map.of("admin", admin));
    }

    /** 工单列表：status=0 待接入 / 1 人工中 / 2 已结束；不传=全部 */
    @GetMapping("/tickets")
    public Result tickets(@RequestParam(value = "status", required = false) Integer status) {
        if (!isAdmin(UserHolder.getUser())) {
            return Result.fail("无权访问客服工作台");
        }
        List<Map<String, Object>> tickets = handoffService.list(status);
        return Result.ok(tickets, (long) tickets.size());
    }

    /** 查看工单完整会话记录（用户 ↔ 客服） */
    @GetMapping("/tickets/{id}/messages")
    public Result messages(@PathVariable("id") Long id) {
        if (!isAdmin(UserHolder.getUser())) {
            return Result.fail("无权访问客服工作台");
        }
        try {
            return Result.ok(handoffService.messages(id));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 接入工单（待接入 → 人工服务中） */
    @PostMapping("/tickets/{id}/claim")
    public Result claim(@PathVariable("id") Long id) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("无权访问客服工作台");
        }
        try {
            SupportHandoff ticket = handoffService.claim(user.getId(), id);
            return Result.ok(Map.of("ticketId", ticket.getId(), "status", ticket.getStatus()));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 人工回复：写入用户私信消息表，用户端轮询可见 */
    @PostMapping("/tickets/{id}/reply")
    public Result reply(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("无权访问客服工作台");
        }
        String content = body == null || body.get("content") == null ? "" : String.valueOf(body.get("content"));
        try {
            handoffService.reply(user.getId(), id, content);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 结束人工会话并恢复 AI */
    @PostMapping("/tickets/{id}/close")
    public Result close(@PathVariable("id") Long id) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("无权访问客服工作台");
        }
        try {
            handoffService.close(user.getId(), id, false);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 客服域指标：运行量、意图分布、转人工、护栏拦截、反馈正负、待接入队列 */
    @GetMapping("/metrics")
    public Result metrics() {
        if (!isAdmin(UserHolder.getUser())) {
            return Result.fail("无权访问客服工作台");
        }
        Map<String, Object> out = new LinkedHashMap<>(supportMetrics.snapshot());
        out.put("waitingCount", handoffService.waitingCount());
        return Result.ok(out);
    }

    /** 【客服Agent完善】差评/bad case 回收：helpful=false 只看差评，关联用户与原始回复 */
    @GetMapping("/feedback")
    public Result feedback(@RequestParam(value = "helpful", required = false) Boolean helpful,
                           @RequestParam(value = "limit", required = false) Integer limit) {
        if (!isAdmin(UserHolder.getUser())) {
            return Result.fail("无权访问客服工作台");
        }
        return Result.ok(feedbackService.list(helpful, limit));
    }

    /** 知识库统计：来源、条目数、标题列表、更新时间 */
    @GetMapping("/kb")
    public Result kb() {
        if (!isAdmin(UserHolder.getUser())) {
            return Result.fail("无权访问客服工作台");
        }
        return Result.ok(knowledgeBase.stats());
    }

    /** 【客服Agent完善】热重载知识库：运营编辑外部 Markdown 后无需重启 */
    @PostMapping("/kb/reload")
    public Result reloadKb() {
        if (!isAdmin(UserHolder.getUser())) {
            return Result.fail("无权访问客服工作台");
        }
        try {
            return Result.ok(knowledgeBase.reload());
        } catch (Exception e) {
            log.warn("客服知识库重载失败: {}", e.getMessage());
            return Result.fail("知识库重载失败：" + e.getMessage());
        }
    }

    private boolean isAdmin(UserDTO user) {
        return user != null && user.getId() != null && supportProps.getAdminUserIds().contains(user.getId());
    }
}
