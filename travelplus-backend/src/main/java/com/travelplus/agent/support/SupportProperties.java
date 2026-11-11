package com.travelplus.agent.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 【企业级客服Agent的实现】客服域配置（前缀 travelplus.support）。
 * <p>
 * 实现细节：
 * - adminUserIds：客服工作台管理员白名单（演示环境默认 1 号用户），生产应替换为 RBAC 角色；
 * - rateLimitPerMinute：客服咨询限流（与旅游助手限流独立，避免互相挤占额度）；
 * - handoffTtlHours：人工会话状态在 Redis 的保留时长；
 * - feedbackEnabled：用户点赞/点踩开关；
 * - maxMessageLength：单条咨询长度上限（输入护栏截断）。
 *
 * @author TravelPlus
 */
@Data
@Component
@ConfigurationProperties(prefix = "travelplus.support")
public class SupportProperties {

    /** 客服工作台管理员用户 id 白名单 */
    private List<Long> adminUserIds = new ArrayList<>(List.of(1L));

    /** 单用户每分钟最大咨询次数 */
    private int rateLimitPerMinute = 30;

    /** 人工会话状态保留时长（小时） */
    private int handoffTtlHours = 72;

    /** 用户反馈开关 */
    private boolean feedbackEnabled = true;

    /** 单条咨询最大长度 */
    private int maxMessageLength = 2000;

    /**
     * 【客服Agent完善】外部知识库 Markdown 路径（可选）。
     * 配置后优先读取该文件，运营可直接编辑并调用工作台「重载知识库」热更新；
     * 留空则回退 classpath:knowledge/support-faq.md。
     */
    private String knowledgePath = "";
}
