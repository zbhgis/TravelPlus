package com.travelplus.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【AI客服的实现】AI 大模型接入配置属性类，绑定 application.yaml 中 travelplus.ai 前缀的配置项。
 * <p>
 * 实现细节：
 * - 三个字段分别对应：apiKey=DeepSeek 平台密钥、baseUrl=OpenAI 兼容端点（https://api.deepseek.com）、
 *   model=使用的模型名（deepseek-v4-flash）。
 * - 密钥只放在 yaml 配置中，不硬编码进 Java 常量；日志中禁止打印完整密钥，避免泄露。
 * - 由 SupportServiceImpl 构造时读取，用于构建 langchain4j 的 OpenAiChatModel。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travelplus.ai")
public class AiProperties {
    /**
     * DeepSeek 平台 API 密钥（测试环境配置值，生产应替换为环境变量/密钥中心注入）
     */
    private String apiKey;

    /**
     * OpenAI 兼容端点 Base URL（DeepSeek 官方同时接受 https://api.deepseek.com 与 /v1 后缀，
     * OpenAiChatModel 会在其后拼接 /chat/completions）
     */
    private String baseUrl;

    /**
     * DeepSeek 模型名
     */
    private String model;
}
