package com.travelplus.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【Agent架构升级的实现】Agent 运行时治理配置（前缀 travelplus.ai.agent，application.yaml 可覆盖）。
 * <p>
 * 实现细节：
 * - 全部字段带代码内默认值：yaml 未配置也能以生产合理值运行（约定优于配置）；
 * - 预算类（maxRounds/maxToolCalls/runTimeoutSeconds）对应生产清单的
 *   "hard caps on sequential tool cycles + wall-clock budget"，防模型循环失控烧 token；
 * - 治理类（rateLimit/breaker/memory）对应"per-tenant rate limiting、circuit breaker、
 *   bounded conversation memory"三项企业级能力。
 *
 * @author TravelPlus
 */
@Data
@Component
@ConfigurationProperties(prefix = "travelplus.ai.agent")
public class AgentProperties {

    /** 工具调用循环最大轮数（一轮 = 一次 LLM 调用，可含多个 tool_calls） */
    private int maxRounds = 5;

    /** 单次对话内工具执行总次数上限（跨轮累计，防预算失控） */
    private int maxToolCalls = 8;

    /** 单次对话硬性死线（秒）：超时礼貌收尾，对齐 SseEmitter 120s 超时留缓冲 */
    private int runTimeoutSeconds = 100;

    /** 采样温度 */
    private double temperature = 0.7;

    /** 单用户/IP 限流：时间窗口内最大对话请求数 */
    private int rateLimitPerMinute = 20;

    /** 限流时间窗口（秒） */
    private int rateLimitWindowSeconds = 60;

    /** 服务端会话记忆 TTL（小时，滑动续期） */
    private int memoryTtlHours = 24;

    /** 会话记忆保留的最大 user/assistant 消息对数（超出丢最旧，控制 token 用量） */
    private int memoryMaxMessages = 20;

    /** 熔断器：连续失败多少次后打开 */
    private int breakerFailureThreshold = 3;

    /** 熔断器：打开后多少秒进入半开探测 */
    private int breakerOpenSeconds = 30;

    /** LLM 调用失败（网络/5xx/429）自动重试次数（硬性上限，指数不放大） */
    private int llmRetryTimes = 1;

    /** LLM 重试退避基数（毫秒） */
    private long llmRetryBackoffMs = 500;
}
