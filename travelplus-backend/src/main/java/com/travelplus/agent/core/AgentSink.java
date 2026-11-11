package com.travelplus.agent.core;

/**
 * 【客服Agent升级的实现】Agent 事件输出抽象。
 * <p>
 * 实现细节：
 * - 编排层只依赖本接口，不感知具体传输方式：旅游助手用 {@link SseSink} 输出 SSE 事件，
 *   客服 Agent 用 {@link SilentSink} 丢弃事件并直接取最终文本（兼容现有「提交 + 轮询私信表」契约）。
 * - 事件语义与 SSE v2 协议一致：meta/delta/tool/error/done，后续接入其他通道（WebSocket/消息队列）
 *   只需新增一个实现，编排循环保持不变。
 *
 * @author TravelPlus
 */
public interface AgentSink {

    /** 首事件：运行 id 与服务端会话 id */
    void meta(String runId, String sessionId);

    /** 正文增量 */
    void delta(String text);

    /**
     * 思考态事件：每轮 LLM 往返前推送——工具步骤全部 ✓ 后正文有 2~5s 空窗，
     * 前端据此展示"正在思考…"动画行（首个 delta / 下一个 tool 事件即移除），
     * 消除"一直在检索没下文"的观感。default 空实现：非流式 Sink（SilentSink）无需关心。
     */
    default void thinking() {
    }

    /** 工具执行时间线事件 */
    void tool(String name, String label, String status, Long ms);

    /** 类型化错误事件 */
    void error(String code, String message);

    /**
     * 【企业级客服Agent的实现】正文替换事件：流式内容被输出护栏修正（脱敏/泄露拦截）时，
     * 前端用该文本替换已渲染正文，保证流式体验与安全护栏不冲突。
     */
    default void replace(String text) {
    }

    /** 收敛事件 */
    void done();

    /**
     * 【企业级客服Agent的实现】带消息 id 的收敛事件：客服流式回复落库后回传 id，
     * 前端据此给流式气泡挂载 👍/👎 反馈按钮。
     */
    default void done(Long messageId) {
        done();
    }
}
