package com.travelplus.service;

import com.travelplus.dto.Result;

/**
 * 【AI客服的实现】AI 智能客服服务接口。
 * <p>
 * 实现细节：
 * - chat(message, userId)：接收用户消息（可选携带当前登录用户 id），调用大模型生成回复，
 *   失败时统一降级返回"客服暂时不可用"，不向调用方暴露底层异常。
 * - 单轮无状态对话：每次调用独立组装系统提示 + 用户消息，不维护会话历史；
 *   后续如需多轮上下文，可在 service 内按 userId 缓存历史消息列表。
 *
 * @author TravelPlus
 */
public interface ISupportService {

    /**
     * 【AI客服的实现】与 AI 客服对话：组装系统提示 + 用户消息 → 调用 DeepSeek → 返回回复文本
     * <p>
     * 实现细节：
     * - 系统提示固定为 TravelPlus 平台智能客服人设与功能覆盖（登录/景点/游记/秒杀/订单/签到/关注等）。
     * - 用户消息拼接原始输入；userId 非空时附加"当前用户 id"，便于客服给出针对性回答。
     * - AI 调用任何异常（网络不通/密钥无效/模型名错误）都在 impl 内捕获降级，不抛到全局异常处理。
     * @param message 用户输入消息（必填）
     * @param userId  当前登录用户 id（可为 null，未登录时仅发匿名消息）
     * @return success=true 时 data=客服回复文本；失败时 errorMsg="客服暂时不可用，请稍后再试"
     */
    Result chat(String message, Long userId);
}
