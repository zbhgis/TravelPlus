package com.travelplus.agent.core;

import java.util.Set;
import java.util.function.Function;

/**
 * 【企业级客服Agent的实现】Agent 运行档案：提示词 + 数据接地 + 长度策略 + 工具白名单。
 * <p>
 * 实现细节：
 * - name：档案标识（travel/support-ORDER 等），用于日志排查；
 * - promptName：PromptRegistry 中版本化提示词模板名；
 * - planningLengthPolicy：是否启用规划类长文长度策略（仅旅游攻略助手需要）；
 * - contextProvider：按本轮用户消息构建接地上下文（旅游=城市景点，客服=平台知识库）；
 * - allowedTools：工具白名单（最小权限原则）。为空/null 表示不限制（旅游助手兼容路径），
 *   非空时既过滤模型可见的工具 schema，也拦截模型幻觉出的白名单外工具调用。
 *
 * @author TravelPlus
 */
public record AgentProfile(String name, String promptName, boolean planningLengthPolicy,
                           Function<String, String> contextProvider, Set<String> allowedTools) {

    /** 兼容旧调用（不限制工具） */
    public AgentProfile(String name, String promptName, boolean planningLengthPolicy,
                        Function<String, String> contextProvider) {
        this(name, promptName, planningLengthPolicy, contextProvider, null);
    }
}
