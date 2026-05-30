package com.travelplus.dto;

import lombok.Data;

/**
 * 【AI客服的实现】AI 客服对话入参 DTO：POST /support/chat 请求体 {message}。
 * message 为用户输入文本（需登录后携带 authorization 头调用，前端客服入口在个人主页）。
 */
@Data
public class ChatDTO {
    /**
     * 用户输入的消息内容
     */
    private String message;
}
