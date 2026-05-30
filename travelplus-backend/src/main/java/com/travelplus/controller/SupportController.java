package com.travelplus.controller;

import com.travelplus.dto.ChatDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.service.ISupportService;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 【AI客服的实现】AI 智能客服接口：与 DeepSeek 大模型对话。
 * <p>
 * 实现细节：
 * - 路由：POST /support/chat，body {message} → Result{success, data=客服回复}；
 *   失败时 data 为 null、errorMsg="客服暂时不可用，请稍后再试"。
 * - 鉴权：/support/** 不在 MvcConfig LoginInterceptor 的 excludePathPatterns 放行列表中，
 *   因此本接口需登录访问（未登录返回 401）；当前登录用户从 UserHolder.getUser() 获取，
 *   客服入口位于个人主页（登录后可见），符合预期。
 * - 当前用户 id 透传给 service，便于客服给出针对性回答（未登录为 null，正常调用不会发生）。
 * - 【AI客服的实现】客服会话不受私信限制约束：/support/chat 是独立接口，直接调 supportService.chat
 *   （内部走 DeepSeek 大模型，不经 IChatService/ChatServiceImpl.send），因此不经过 /chat/send 的
 *   私信模式（dm_mode）三档限制与陌生人"回复前每日限条"逻辑——任何已登录用户随时可咨询客服，
 *   客服回复落 tb_chat_message 仅为会话展示，不触发私信限制校验。
 *
 * @author TravelPlus
 */
@Slf4j
@RestController
@RequestMapping("/support")
public class SupportController {

    @Resource
    private ISupportService supportService;

    /**
     * 【AI客服的实现】AI 客服对话接口：接收用户消息，返回大模型生成的回复
     * @param chatDTO 请求体 {message}（用户消息）
     * @return success=true 时 data=客服回复文本；AI 调用失败降级为 fail
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody ChatDTO chatDTO) {
        // 从登录态获取当前用户 id（RefreshTokenInterceptor 写入 UserHolder，拦截器已保证已登录）
        UserDTO user = UserHolder.getUser();
        Long userId = user != null ? user.getId() : null;
        return supportService.chat(chatDTO.getMessage(), userId);
    }
}
