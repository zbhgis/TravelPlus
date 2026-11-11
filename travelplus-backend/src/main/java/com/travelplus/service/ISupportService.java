package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 【客服Agent升级的实现】AI 智能客服服务接口。
 * <p>
 * 实现细节：
 * - chat(message, user)：接收用户消息与登录态，提交客服 Agent 异步生成回复（多轮记忆 + 知识库 RAG +
 *   订单/积分/秒杀工具），回复写入私信消息表，前端继续轮询 /chat/list?peerId=1021。
 * - clear(userId)：清空该用户的客服 Agent 会话记忆（界面「清空对话」时调用）。
 *
 * @author TravelPlus
 */
public interface ISupportService {

    /**
     * 与 AI 客服对话（异步）：校验 + 用户消息落库 + 提交 Agent 任务，立即返回「思考中」。
     *
     * @param message 用户输入消息（必填）
     * @param user    当前登录用户（工具身份注入与订单查询基于它）
     * @return success=true 表示已受理；空消息返回 fail
     */
    Result chat(String message, UserDTO user);

    /**
     * 【企业级客服的实现】流式咨询（SSE）：meta/delta/tool/replace/error/done 结构化事件。
     *
     * @param message 用户输入消息
     * @param user    当前登录用户
     * @return SSE emitter（done 事件携带落库消息 id）
     */
    SseEmitter chatStream(String message, UserDTO user);

    /**
     * 清空客服 Agent 会话记忆。
     *
     * @param userId 当前登录用户 id
     * @return 成功返回 ok；未登录返回 fail
     */
    Result clear(Long userId);
}
