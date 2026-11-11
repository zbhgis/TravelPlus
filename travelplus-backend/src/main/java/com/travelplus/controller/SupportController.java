package com.travelplus.controller;

import com.travelplus.agent.support.SupportFeedbackService;
import com.travelplus.agent.support.SupportHandoffService;
import com.travelplus.agent.support.SupportSessionStore;
import com.travelplus.dto.ChatDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.SupportHandoff;
import com.travelplus.service.ISupportService;
import com.travelplus.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【企业级客服Agent的实现】AI 客服用户侧接口。
 * <p>
 * 路由：
 * - POST   /support/chat      提交咨询（返回当前 mode：AI/WAITING/HUMAN）
 * - GET    /support/status    查询当前会话模式与人工工单状态
 * - POST   /support/handoff   用户主动转人工
 * - POST   /support/resume    用户结束人工、恢复 AI
 * - POST   /support/feedback  对客服回复点赞/点踩
 * - DELETE /support/session   清空 Agent 记忆（不影响已提交的人工工单）
 * <p>
 * /support/** 在登录拦截范围内，以下接口均要求登录。
 *
 * @author TravelPlus
 */
@Slf4j
@RestController
@RequestMapping("/support")
public class SupportController {

    @Resource
    private ISupportService supportService;

    @Resource
    private SupportHandoffService handoffService;

    @Resource
    private SupportFeedbackService feedbackService;

    /** 提交咨询：异步生成回复，消息经 /chat/list?peerId=1021 轮询获取 */
    @PostMapping("/chat")
    public Result chat(@RequestBody ChatDTO chatDTO) {
        return supportService.chat(chatDTO.getMessage(), UserHolder.getUser());
    }

    /**
     * 【企业级客服的实现】流式咨询：与旅行 AI 助手一致的 SSE 输出。
     * 事件：meta / delta / tool / replace / error / done（done 携带 messageId）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatDTO chatDTO) {
        return supportService.chatStream(chatDTO.getMessage(), UserHolder.getUser());
    }

    /** 当前会话状态：mode=AI/WAITING/HUMAN、工单号、待接入人数 */
    @GetMapping("/status")
    public Result status() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(handoffService.status(user.getId()));
    }

    /** 用户主动转人工：创建/复用工单并写一条客服回执 */
    @PostMapping("/handoff")
    public Result handoff(@RequestBody(required = false) Map<String, Object> body) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        String reason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        if (reason.isBlank() || "null".equals(reason)) {
            reason = "用户主动请求人工";
        }
        SupportHandoff ticket = handoffService.request(user.getId(), reason, "user");
        int waiting = handoffService.waitingCount();
        handoffService.notifyUser(user.getId(),
                "已为你转接人工客服（工单号 " + ticket.getId() + "），当前待接入 " + waiting + " 人，请稍候。");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", SupportSessionStore.MODE_WAITING);
        out.put("ticketId", ticket.getId());
        out.put("waitingCount", waiting);
        return Result.ok(out);
    }

    /** 用户结束人工会话、恢复 AI */
    @PostMapping("/resume")
    public Result resume() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Result result = handoffService.resumeByUser(user.getId());
        if (result.getSuccess() != null && result.getSuccess()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", SupportSessionStore.MODE_AI);
            return Result.ok(out);
        }
        return result;
    }

    /** 对客服回复点赞/点踩 */
    @PostMapping("/feedback")
    public Result feedback(@RequestBody Map<String, Object> body) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Long messageId = body.get("messageId") instanceof Number n ? n.longValue() : null;
        Boolean helpful = body.get("helpful") instanceof Boolean b ? b : null;
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        String intent = body.get("intent") == null ? null : String.valueOf(body.get("intent"));
        return feedbackService.submit(user.getId(), messageId, helpful, reason, intent);
    }

    /** 清空 Agent 记忆（界面「清空对话」；人工工单状态保留） */
    @DeleteMapping("/session")
    public Result clearSession() {
        UserDTO user = UserHolder.getUser();
        return supportService.clear(user == null ? null : user.getId());
    }
}
